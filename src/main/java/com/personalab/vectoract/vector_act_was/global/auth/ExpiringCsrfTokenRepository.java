package com.personalab.vectoract.vector_act_was.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.*;
import org.springframework.stereotype.Component;
import java.time.*;

/**
 * Spring의 세션 기반 CSRF 저장소에 만료 시각을 추가합니다.
 * DB Repository가 아니라 보안 필터가 사용하는 세션 저장소입니다.
 * 토큰 생성·저장은 기존 구현에 맡기고, 만료된 토큰을 불러오지 않는 규칙만 보강합니다.
 */
@Component
public class ExpiringCsrfTokenRepository implements CsrfTokenRepository {
    private static final String EXPIRES_AT = ExpiringCsrfTokenRepository.class.getName() + ".EXPIRES_AT";
    private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();
    private final Duration lifetime;
    private final Clock clock;

    @Autowired
    public ExpiringCsrfTokenRepository(@Value("${auth.csrf.ttl-seconds:86400}") long seconds) {
        this(seconds, Clock.systemUTC());
    }

    // 테스트는 Clock을 고정하여 실제로 24시간 기다리지 않고 만료 경계를 확인합니다.
    public ExpiringCsrfTokenRepository(long seconds, Clock clock) {
        if (seconds <= 0) throw new IllegalArgumentException("CSRF lifetime must be positive");
        this.lifetime = Duration.ofSeconds(seconds);
        this.clock = clock;
        delegate.setHeaderName("X-CSRF-TOKEN");
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        delegate.saveToken(token, request, response);
        var session = request.getSession(false);
        if (session == null) return;
        if (token == null) {
            // 토큰을 지울 때 만료 시각도 함께 지웁니다. 삭제 목적으로 새 세션을 만들지는 않습니다.
            session.removeAttribute(EXPIRES_AT);
        } else {
            // 발급 시각을 기준으로 고정된 만료 시각입니다. 다른 요청이 와도 자동 연장하지 않습니다.
            session.setAttribute(EXPIRES_AT, clock.instant().plus(lifetime));
        }
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute(EXPIRES_AT);
        // 만료 정보가 없는 이전 형식의 세션도 재발급 대상으로 취급합니다.
        // now == expiresAt인 경계부터 만료입니다. 만료된 토큰으로 POST하면 CSRF 필터가 403을 반환합니다.
        if (!(value instanceof Instant expiry) || !clock.instant().isBefore(expiry)) return null;
        return delegate.loadToken(request);
    }

    public OffsetDateTime getExpiresAt(HttpServletRequest request) {
        // Controller는 CsrfToken.getToken()으로 지연 생성을 완료한 뒤 이 메서드를 호출해야 합니다.
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(EXPIRES_AT) instanceof Instant expiry)) {
            throw new IllegalStateException("CSRF token has not been saved");
        }
        return expiry.atOffset(ZoneOffset.UTC);
    }
}
