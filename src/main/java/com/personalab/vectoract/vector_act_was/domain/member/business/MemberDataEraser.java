package com.personalab.vectoract.vector_act_was.domain.member.business;

import java.util.UUID;

/**
 * 실제 영상 저장소와 분석 데이터 모듈이 구현할 삭제 계약입니다.
 * 외부 저장소 삭제는 이미 없는 파일도 성공으로 처리해야 재시도가 가능합니다.
 * DB 삭제는 호출자의 트랜잭션에 참여해야 하며 독립 커밋하면 안 됩니다.
 */
public interface MemberDataEraser {
    void deleteExternalData(UUID userId);
    void deleteDatabaseData(UUID userId);
}
