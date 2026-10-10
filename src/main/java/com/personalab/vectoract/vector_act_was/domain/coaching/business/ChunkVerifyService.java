package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunk;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunkRepository;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.UserRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * C05 Chunk 업로드 검증 완료. 클라이언트가 업로드를 마쳤다고 알리면, 저장소에 실제로 올라간 객체의
 * 크기·SHA-256을 C04에서 선언한 값과 대조해 일치할 때만 청크를 VERIFIED로 확정합니다.
 *
 * <p>"클라이언트가 말한 값을 믿지 않고 저장소의 실제 객체를 확인한다"는 점이 핵심입니다. 이 단계를 통과한
 * 청크만 이후 최종 영상 조립(C07 이후)의 입력이 됩니다.
 */
@Service
public class ChunkVerifyService {
    private final UserRepository users;
    private final CoachingSessionRepository sessions;
    private final SessionChunkRepository chunks;
    private final VideoStorage storage;

    public ChunkVerifyService(UserRepository users, CoachingSessionRepository sessions,
                              SessionChunkRepository chunks, VideoStorage storage) {
        this.users = users;
        this.sessions = sessions;
        this.chunks = chunks;
        this.storage = storage;
    }

    /** 컨트롤러가 응답으로 바꿀 결과입니다. {@code duplicate}는 이미 검증이 끝난 청크를 다시 확인했을 때 true입니다. */
    public record Result(int chunkIndex, boolean duplicate) {}

    /**
     * 검사 순서: 청크 번호 범위(400) → 소유·존재(404) → 세션 상태(409, 410) → 이미 검증됨(중복) →
     * 객체 존재(409 CHUNK_NOT_UPLOADED) → 크기·해시(409 CHECKSUM_MISMATCH).
     *
     * <p>저장소 조회(외부 호출)를 회원 행 잠금 안에서 실행합니다. 같은 회원의 다른 청크 요청이 잠시 기다리지만,
     * 청크 상태 확정을 직렬화해 같은 청크를 두 번 확정하는 경합을 막는 쪽을 택했습니다.
     */
    @Transactional
    public Result complete(UUID userId, UUID sessionId, int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex > ChunkUploadService.MAX_CHUNK_INDEX) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // C04와 같은 규칙: 촬영 중이거나, 종료 후 마감 전(FINALIZING)일 때만 검증할 수 있습니다.
        ChunkUploadService.requireUploadable(session, now);

        // 예약(C04)된 적 없는 청크 번호는 존재하지 않는 자원으로 취급합니다.
        var chunk = chunks.findBySessionIdAndChunkIndex(sessionId, chunkIndex)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 이미 검증된 청크: 다시 호출해도 같은 결과를 돌려주고 duplicate로 알립니다(멱등).
        if (chunk.getStatus() == SessionChunk.Status.VERIFIED) {
            return new Result(chunkIndex, true);
        }

        var stored = storage.stat(chunk.getObjectKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHUNK_NOT_UPLOADED));
        if (stored.sizeBytes() != chunk.getSizeBytes() || !stored.sha256Hex().equals(chunk.getSha256())) {
            // 선언과 다른 파일이 올라간 경우입니다. 잘못된 객체를 남기지 않도록 지우고 예약은 유지해,
            // 클라이언트가 C04로 URL을 다시 받아 올바른 파일을 올릴 수 있게 합니다.
            // (이 요청은 예외로 끝나 롤백되지만, 저장소 삭제는 DB 롤백과 무관하게 이미 반영됩니다.)
            storage.delete(chunk.getObjectKey());
            throw new BusinessException(ErrorCode.CHECKSUM_MISMATCH);
        }
        chunk.markVerified(now);
        return new Result(chunkIndex, false);
    }
}
