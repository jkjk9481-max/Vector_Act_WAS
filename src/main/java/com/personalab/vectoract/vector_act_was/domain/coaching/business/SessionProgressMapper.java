package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunk;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.SessionChunkRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.SessionProgressResponse;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * 세션 엔티티를 진행 상태 응답({@link SessionProgressResponse})으로 바꿉니다. C07·C08·C09가 공유합니다.
 * 누락 청크 번호를 계산하기 위해 청크를 조회하므로 호출하는 쪽의 트랜잭션 안에서 사용해야 합니다.
 */
@Component
public class SessionProgressMapper {
    private final SessionChunkRepository chunks;

    public SessionProgressMapper(SessionChunkRepository chunks) {
        this.chunks = chunks;
    }

    public SessionProgressResponse toResponse(CoachingSession session) {
        return new SessionProgressResponse(session.getId(), session.getStatus().name(),
                session.getVideoStatus().name(), session.getAnalysisStatus().name(),
                session.getAnalysisMode().name(), missingChunkIndexes(session), session.getUploadDeadlineAt(),
                session.getAnalysisAttempt(), session.getFailureCode());
    }

    /**
     * 종료를 선언하기 전에는 범위가 정해지지 않았으므로 빈 목록입니다.
     * 선언 후에는 0 ~ 선언한 마지막 번호 중 VERIFIED 청크가 없는 번호가 누락입니다.
     */
    List<Integer> missingChunkIndexes(CoachingSession session) {
        Integer last = session.getDeclaredLastChunkIndex();
        if (last == null) return List.of();
        var verified = new boolean[last + 1];
        for (SessionChunk chunk : chunks.findBySessionIdOrderByChunkIndexAsc(session.getId())) {
            if (chunk.getStatus() == SessionChunk.Status.VERIFIED && chunk.getChunkIndex() <= last) {
                verified[chunk.getChunkIndex()] = true;
            }
        }
        var missing = new ArrayList<Integer>();
        for (int i = 0; i <= last; i++) if (!verified[i]) missing.add(i);
        return List.copyOf(missing);
    }
}
