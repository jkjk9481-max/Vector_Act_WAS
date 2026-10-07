package com.personalab.vectoract.vector_act_was.domain.member.business;

import java.util.UUID;

/**
 * 실제 영상 저장소와 분석 데이터 모듈이 구현할 삭제 계약입니다.
 * 외부 저장소 삭제는 이미 없는 파일도 성공으로 처리해야 재시도가 가능합니다.
 * DB 삭제는 호출자의 트랜잭션에 참여해야 하며 독립 커밋하면 안 됩니다.
 */
public interface MemberDataEraser {
    // 외부 저장소 담당: 해당 회원 영상 파일을 모두 지운 뒤에만 정상 반환합니다.
    // 일부 파일만 삭제되거나 저장소 연결이 실패하면 예외를 던져 DB 삭제를 막아야 합니다.
    void deleteExternalData(UUID userId);

    // DB 담당: 영상·분석 등 추가 테이블을 해당 모듈의 Repository로 삭제합니다.
    // 이 인터페이스 선언만으로 실제 삭제가 수행되지는 않습니다. 구현체를 Spring Bean으로 등록해야 합니다.
    // REQUIRES_NEW처럼 별도 트랜잭션을 열지 않고 MemberPurgeService의 트랜잭션에 참여해야 합니다.
    void deleteDatabaseData(UUID userId);
}
