package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionVideo;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionVideoRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionCreateRequest;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionResponse;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionStartRequest;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKey;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 연습(코칭) 세션의 업무 규칙을 담당합니다.
 * <ul>
 *   <li>C01 {@link #create}: 설정만 저장해 세션을 준비합니다(상태 CREATED).</li>
 *   <li>C02 {@link #start}: 준비된 세션의 촬영 시작을 접수합니다(CREATED → RECORDING).</li>
 * </ul>
 *
 * <p>공통 규칙: 모든 변경 메서드는 회원(users) 행을 먼저 잠급니다. 이 프로젝트의 동시성 규칙이며,
 * 같은 회원의 요청이 한 줄로 직렬화되므로 "활성 세션 검사 → 저장" 같은 확인 후 쓰기(check-then-act)
 * 구간에 다른 요청이 끼어들 수 없습니다.
 */
@Service
public class CoachingSessionService {
    // idempotency_keys.request_scope 값입니다. 같은 키라도 API 기능(범위)이 다르면 별개의 기록입니다.
    static final String SCOPE_CREATE = "COACHING_SESSION_CREATE";

    // 대본 1~20000자, 상황 1~2000자 (API 명세서 C01, DB CHECK와 동일).
    private static final int SCRIPT_MAX = 20000;
    private static final int SITUATION_MAX = 2000;

    // API 명세서 C02가 허용하는 입력 MIME입니다. 비교는 공백 제거·소문자 변환 후 하므로 소문자로 둡니다.
    private static final List<String> SUPPORTED_MIME_TYPES = List.of(
            "video/webm;codecs=vp8,opus", "video/mp4;codecs=avc1.42e01e,mp4a.40.2");

    private final UserRepository users;
    private final CoachingSessionRepository sessions;
    private final SessionVideoRepository videos;
    private final IdempotencyKeyRepository idempotencyKeys;
    // 멱등 기록에 저장하는 응답 본문을 JSON 문자열로 바꾸고 되돌리는 데 사용합니다.
    private final ObjectMapper objectMapper;
    // 멱등 기록 보관 시간입니다. DB 설계서가 "운영 정책"으로만 정해 설정값(기본 24시간)으로 둡니다.
    private final long idempotencyTtlHours;

    public CoachingSessionService(UserRepository users, CoachingSessionRepository sessions,
                                  SessionVideoRepository videos,
                                  IdempotencyKeyRepository idempotencyKeys, ObjectMapper objectMapper,
                                  @Value("${idempotency.ttl-hours:24}") long idempotencyTtlHours) {
        this.users = users;
        this.sessions = sessions;
        this.videos = videos;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    /**
     * C01 연습 세션 준비.
     * 새로 만들었거나, 같은 키·같은 본문의 재시도로 저장된 응답을 복원한 결과를 반환합니다(상태 코드는 둘 다 201).
     *
     * <p>처리 순서(순서가 중요합니다):
     * <ol>
     *   <li>입력 값 검증 — DB를 건드리지 않는 검사를 가장 먼저 합니다.</li>
     *   <li>회원 행 잠금 — 이후 단계를 같은 회원 기준으로 직렬화합니다.</li>
     *   <li>멱등 기록 확인 — 재시도라면 활성 세션 검사보다 먼저 응답을 복원해야 합니다.
     *       그렇지 않으면 성공했던 요청의 재시도가 ACTIVE_SESSION_EXISTS(409)로 바뀌어 버립니다.</li>
     *   <li>활성 세션 검사 → 세션 저장 → 멱등 기록 저장 — 모두 한 트랜잭션이라 하나라도 실패하면 함께 롤백됩니다.</li>
     * </ol>
     */
    @Transactional
    public CoachingSessionResponse create(UUID userId, UUID idempotencyKey, CoachingSessionCreateRequest request) {
        requireLength(request.scriptContent(), SCRIPT_MAX);
        requireLength(request.situation(), SITUATION_MAX);
        var coaching = request.coaching();
        // analysisOnly=true 이면 시각·음성 코칭은 모두 꺼져 있어야 합니다(둘 중 하나라도 켜져 있으면 설정 충돌).
        if (coaching.analysisOnly() && (coaching.visualEnabled() || coaching.voiceEnabled())) {
            throw new BusinessException(ErrorCode.COACHING_CONFIG_CONFLICT);
        }

        // 회원 행을 PESSIMISTIC_WRITE로 잠급니다. 같은 회원의 다른 요청은 이 트랜잭션이 끝날 때까지 여기서 대기합니다.
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String hash = hash(request);

        var existing = idempotencyKeys
                .findByUserIdAndIdempotencyKeyAndRequestScope(userId, idempotencyKey, SCOPE_CREATE).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt().isAfter(now)) {
                // 같은 키로 다른 내용을 보냈다면 클라이언트 실수이므로 충돌로 알립니다.
                if (!existing.getRequestHash().equals(hash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                // 같은 요청의 재시도: 세션을 새로 만들지 않고 처음 응답을 그대로 돌려줍니다.
                return objectMapper.readValue(existing.getResponseBody(), CoachingSessionResponse.class);
            }
            // 만료된 기록은 같은 키를 새 요청으로 다시 쓸 수 있도록 지웁니다.
            // flush로 DELETE를 먼저 반영해야 아래에서 같은 (회원, 키, 범위)를 INSERT해도 UNIQUE에 걸리지 않습니다.
            idempotencyKeys.delete(existing);
            idempotencyKeys.flush();
        }

        // 회원당 활성 세션(CREATED/RECORDING/FINALIZING)은 1개입니다.
        // 운영 DB에는 같은 규칙의 partial unique index가 최종 방어선으로 있습니다.
        if (sessions.existsByUserIdAndDeletedAtIsNullAndStatusIn(userId, CoachingSession.ACTIVE_STATUSES)) {
            throw new BusinessException(ErrorCode.ACTIVE_SESSION_EXISTS);
        }

        var session = sessions.saveAndFlush(CoachingSession.prepare(userId, request.scriptContent(),
                request.situation(), coaching.visualEnabled(), coaching.voiceEnabled(), coaching.analysisOnly(),
                coaching.intensity(), now));
        var response = CoachingSessionResponse.of(session);
        // 응답 본문까지 함께 저장해 두어야 재시도에서 "동일한 응답"을 복원할 수 있습니다.
        idempotencyKeys.saveAndFlush(IdempotencyKey.create(userId, idempotencyKey, SCOPE_CREATE, hash,
                session.getId(), 201, objectMapper.writeValueAsString(response), now,
                now.plusHours(idempotencyTtlHours)));
        return response;
    }

    /**
     * C02 촬영 시작. CREATED 상태의 세션만 RECORDING으로 바꾸고 입력 영상 정보를 기록합니다.
     *
     * <p>오류 검사 순서: 입력 형식(400, 컨트롤러 검증) → 지원 MIME(415) → 소유·존재(404) → 상태(409).
     * 명세에 순서 규정이 없어 "요청 자체의 문제 → 대상의 문제 → 대상의 상태 문제" 순으로 정했습니다.
     *
     * <p>이 API는 멱등 키를 받지 않습니다(API 명세서 C02). 따라서 이미 시작된 세션에 같은 요청을 다시 보내면
     * 성공 응답을 복원하지 않고 SESSION_STATE_CONFLICT(409)가 됩니다.
     */
    @Transactional
    public CoachingSessionResponse start(UUID userId, UUID sessionId, CoachingSessionStartRequest request) {
        // DB를 건드리기 전에 MIME부터 확인합니다. 지원하지 않는 형식이면 세션 상태는 전혀 바뀌지 않습니다.
        String mimeType = canonicalMimeType(request.mimeType());

        // C01과 같은 이유로 회원 행을 먼저 잠급니다. 같은 회원의 start 요청 두 개가 동시에 와도
        // 한쪽은 여기서 기다렸다가 상태가 이미 RECORDING인 것을 보고 409를 받습니다(이중 시작 방지).
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // 세션 ID와 소유자를 한 조건으로 조회합니다. 타인 소유·삭제 표시된·없는 세션이 모두 같은 404가 되어
        // 다른 회원의 세션 ID가 존재하는지 알아낼 수 없습니다(API 명세서 공통 규칙: 타인 소유 ID도 404).
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // 시작할 수 있는 상태는 CREATED 하나뿐입니다. RECORDING(이미 시작), FINALIZING/COMPLETED/FAILED/CANCELED(종료)는 모두 충돌입니다.
        if (session.getStatus() != CoachingSession.Status.CREATED) {
            throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
        }

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // 상태 전이와 시각 계산은 엔티티가 담당합니다(상태 변경 규칙을 한곳에 모으기 위해).
        session.startRecording(now);
        // 입력 영상 정보를 별도 테이블(session_videos)에 기록합니다. 세션당 1행(UNIQUE)이며,
        // 이후 Chunk 업로드·조립 단계(C04~)가 같은 행의 나머지 컬럼을 채웁니다.
        // frameRate는 DB 컬럼(NUMERIC(5,2))에 맞춰 소수 둘째 자리로 반올림합니다. 1~30 범위는 요청 검증에서 이미 확인했습니다.
        videos.saveAndFlush(SessionVideo.started(session.getId(), mimeType, request.width(), request.height(),
                request.frameRate().setScale(2, RoundingMode.HALF_UP), now, session.getVideoExpiresAt()));
        // 세션과 영상 행은 같은 트랜잭션이므로 둘 중 하나가 실패하면 함께 롤백됩니다.
        sessions.saveAndFlush(session);
        return CoachingSessionResponse.of(session);
    }

    /**
     * 요청의 mimeType을 지원 목록의 정식 표기로 바꿉니다. 지원하지 않으면 415입니다.
     * 브라우저가 대소문자나 공백을 다르게 보낼 수 있어, 공백을 모두 지우고 소문자로 맞춘 값으로 비교합니다.
     * (예: " video/MP4; codecs=avc1.42E01E,mp4a.40.2" → "video/mp4;codecs=avc1.42e01e,mp4a.40.2")
     */
    private static String canonicalMimeType(String value) {
        String normalized = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return SUPPORTED_MIME_TYPES.stream().filter(normalized::equals).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /**
     * 문자열 길이가 1~max인지 검사합니다.
     * DB의 CHECK(char_length)는 "문자(코드포인트)" 수를 세지만 Java의 String.length()는 UTF-16 단위를 셉니다.
     * 이모지 같은 문자는 length()로 2가 되므로, DB와 같은 기준이 되도록 codePointCount를 사용합니다.
     */
    private static void requireLength(String value, int max) {
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > max) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    /**
     * 요청 본문의 SHA-256 해시(16진수)를 만듭니다. 멱등 키 재사용 시 "같은 요청인지" 비교하는 데 씁니다.
     * 필드 순서를 고정하고 문자열 앞에 길이를 붙여, 서로 다른 입력이 같은 문자열로 합쳐지는 일(경계 모호성)을 막습니다.
     */
    private static String hash(CoachingSessionCreateRequest r) {
        var c = r.coaching();
        String canonical = r.scriptContent().length() + ":" + r.scriptContent() + "\n"
                + r.situation().length() + ":" + r.situation() + "\n"
                + c.visualEnabled() + "," + c.voiceEnabled() + "," + c.analysisOnly() + "," + c.intensity();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 제공해야 하는 알고리즘이라 정상적으로는 발생하지 않습니다.
            throw new IllegalStateException(e);
        }
    }
}
