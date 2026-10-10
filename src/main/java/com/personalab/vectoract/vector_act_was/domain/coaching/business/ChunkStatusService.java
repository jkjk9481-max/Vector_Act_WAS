package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunk;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunkRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.ChunkStatusResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * C06 Chunk 현황 조회. 클라이언트가 어떤 청크가 검증됐고 어떤 청크를 다시 올려야 하는지 확인하는 읽기 전용 API입니다.
 * 상태를 바꾸지 않으므로 세션 상태와 무관하게 소유자라면 조회할 수 있습니다.
 */
@Service
public class ChunkStatusService {
    private final CoachingSessionRepository sessions;
    private final SessionChunkRepository chunks;

    public ChunkStatusService(CoachingSessionRepository sessions, SessionChunkRepository chunks) {
        this.sessions = sessions;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public ChunkStatusResponse get(UUID userId, UUID sessionId) {
        // 타인 소유·삭제된·없는 세션은 구분되지 않는 같은 404입니다.
        var session = sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var rows = chunks.findBySessionIdOrderByChunkIndexAsc(sessionId);

        var items = rows.stream().map(c -> new ChunkStatusResponse.Item(c.getChunkIndex(), c.getStatus().name(),
                c.getStartMs(), c.getEndMs(), c.getSizeBytes(), c.getSha256())).toList();

        // 선언 범위: 촬영 종료(C07)에서 마지막 청크 번호를 선언했다면 그 값까지, 아직이라면 현재까지 선언된
        // 가장 큰 청크 번호까지입니다. 그 안에서 VERIFIED가 아닌 번호가 누락입니다.
        var missing = new ArrayList<Integer>();
        Integer declared = session.getDeclaredLastChunkIndex();
        if (declared != null || !rows.isEmpty()) {
            int last = declared != null ? declared : rows.get(rows.size() - 1).getChunkIndex();
            var verified = new boolean[last + 1];
            for (SessionChunk c : rows) {
                if (c.getStatus() == SessionChunk.Status.VERIFIED && c.getChunkIndex() <= last) {
                    verified[c.getChunkIndex()] = true;
                }
            }
            for (int i = 0; i <= last; i++) if (!verified[i]) missing.add(i);
        }
        return new ChunkStatusResponse(items, List.copyOf(missing));
    }
}
