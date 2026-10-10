package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionResponse;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKey;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * C01 연습 세션 준비. 설정만 저장하며 촬영은 C02에서 시작합니다.
 * 회원 행을 먼저 잠가 같은 회원의 동시 요청(중복 생성, 활성 세션 검사, 멱등 기록)을 직렬화합니다.
 */
@Service
public class CoachingSessionService {
    static final String SCOPE_CREATE = "COACHING_SESSION_CREATE";
    private static final int SCRIPT_MAX = 20000;
    private static final int SITUATION_MAX = 2000;

    private final UserRepository users;
    private final CoachingSessionRepository sessions;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper objectMapper;
    private final long idempotencyTtlHours;

    public CoachingSessionService(UserRepository users, CoachingSessionRepository sessions,
                                  IdempotencyKeyRepository idempotencyKeys, ObjectMapper objectMapper,
                                  @Value("${idempotency.ttl-hours:24}") long idempotencyTtlHours) {
        this.users = users;
        this.sessions = sessions;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    /** 새로 만들었거나 같은 키·같은 본문의 재시도로 복원한 응답을 반환합니다(상태 코드는 둘 다 201). */
    @Transactional
    public CoachingSessionResponse create(UUID userId, UUID idempotencyKey, CoachingSessionCreateRequest request) {
        requireLength(request.scriptContent(), SCRIPT_MAX);
        requireLength(request.situation(), SITUATION_MAX);
        var coaching = request.coaching();
        // analysisOnly=true 이면 시각·음성 코칭은 모두 꺼져 있어야 합니다.
        if (coaching.analysisOnly() && (coaching.visualEnabled() || coaching.voiceEnabled())) {
            throw new BusinessException(ErrorCode.COACHING_CONFIG_CONFLICT);
        }

        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String hash = hash(request);

        var existing = idempotencyKeys
                .findByUserIdAndIdempotencyKeyAndRequestScope(userId, idempotencyKey, SCOPE_CREATE).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt().isAfter(now)) {
                if (!existing.getRequestHash().equals(hash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return objectMapper.readValue(existing.getResponseBody(), CoachingSessionResponse.class);
            }
            // 만료된 기록은 같은 키를 새 요청으로 다시 쓸 수 있도록 지웁니다.
            idempotencyKeys.delete(existing);
            idempotencyKeys.flush();
        }

        if (sessions.existsByUserIdAndDeletedAtIsNullAndStatusIn(userId, CoachingSession.ACTIVE_STATUSES)) {
            throw new BusinessException(ErrorCode.ACTIVE_SESSION_EXISTS);
        }

        var session = sessions.saveAndFlush(CoachingSession.prepare(userId, request.scriptContent(),
                request.situation(), coaching.visualEnabled(), coaching.voiceEnabled(), coaching.analysisOnly(),
                coaching.intensity(), now));
        var response = CoachingSessionResponse.of(session);
        idempotencyKeys.saveAndFlush(IdempotencyKey.create(userId, idempotencyKey, SCOPE_CREATE, hash,
                session.getId(), 201, objectMapper.writeValueAsString(response), now,
                now.plusHours(idempotencyTtlHours)));
        return response;
    }

    // DB CHECK(char_length)와 같은 기준인 코드포인트 수로 1~max를 검사합니다.
    private static void requireLength(String value, int max) {
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > max) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    // 필드 순서와 길이를 고정한 정규화 문자열의 SHA-256입니다.
    private static String hash(CoachingSessionCreateRequest r) {
        var c = r.coaching();
        String canonical = r.scriptContent().length() + ":" + r.scriptContent() + "\n"
                + r.situation().length() + ":" + r.situation() + "\n"
                + c.visualEnabled() + "," + c.voiceEnabled() + "," + c.analysisOnly() + "," + c.intensity();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
