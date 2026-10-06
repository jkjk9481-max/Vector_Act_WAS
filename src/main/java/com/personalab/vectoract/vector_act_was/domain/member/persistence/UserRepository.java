package com.personalab.vectoract.vector_act_was.domain.member.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

// Repository는 DB 접근을 담당합니다. JpaRepository<User, UUID>는 User 엔티티와 UUID 기본 키를 뜻합니다.
// 인터페이스만 선언하면 Spring Data JPA가 구현체를 만들어 주므로 직접 구현 클래스를 작성하지 않습니다.
public interface UserRepository extends JpaRepository<User, UUID> {

    // A08용 조회입니다. Service의 @Transactional 안에서 호출해야 DB 잠금을 유지할 수 있습니다.
    // PESSIMISTIC_WRITE는 대상 사용자 행에 쓰기 잠금을 잡아 다른 잠금 요청과 변경을 순서대로 처리합니다.
    // 일반 조회를 모두 차단한다는 뜻은 아닙니다. 잠금은 트랜잭션의 커밋/롤백 때 해제됩니다.
    // @Query는 테이블 이름이 아닌 엔티티 이름(User)과 필드(id)를 사용하는 JPQL입니다.
    // :id 자리에는 @Param("id")로 연결한 메서드 인자가 들어갑니다.
    // Optional<User>는 회원이 있을 수도, 없을 수도 있음을 표현합니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") UUID id);

    // A03 로그인도 A08과 같은 사용자 행을 잠가야 비밀번호 확인과 토큰 발급 사이에 경합이 없습니다.
    // 로그인이 먼저 끝나면 A08이 그 토큰까지 폐기하고, A08이 먼저 끝나면 로그인은 새 해시로 검증합니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> lockByEmail(@Param("email") String email);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
