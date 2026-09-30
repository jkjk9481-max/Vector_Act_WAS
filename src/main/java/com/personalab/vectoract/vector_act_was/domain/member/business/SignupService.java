package com.personalab.vectoract.vector_act_was.domain.member.business;

import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

// Spring이 이 클래스를 객체(Bean)로 만들어 관리합니다. Controller는 이 객체를 주입받아 사용합니다.
@Service
// 기본은 조회용 트랜잭션입니다. 아래 signup()에는 별도 @Transactional을 붙여 쓰기를 허용합니다.
@Transactional(readOnly = true)
public class SignupService {
    // Repository는 DB 접근 담당입니다. 실제 구현체는 Spring Data JPA가 만들어 줍니다.
    // final은 생성자에서 정한 참조를 다른 객체로 바꾸지 못하게 합니다.
    private final UserRepository users;
    private final UserConsentRepository consents;
    // 기존 PasswordEncoderConfig가 등록한 BCrypt 인코더를 사용합니다.
    private final PasswordEncoder passwordEncoder;
    // 서버가 현재 허용하는 문서 버전입니다. 요청으로 받은 버전과 비교하는 기준입니다.
    private final String termsVersion;
    private final String privacyVersion;

    // 생성자 주입: Spring이 필요한 Repository와 인코더를 찾아 인자로 넣어 줍니다.
    // @Value는 application.yaml의 설정값을 읽습니다. this는 현재 Service 객체를 가리킵니다.
    public SignupService(UserRepository users, UserConsentRepository consents, PasswordEncoder passwordEncoder,
                         @Value("${signup.terms-version}") String termsVersion,
                         @Value("${signup.privacy-version}") String privacyVersion) {
        this.users = users;
        this.consents = consents;
        this.passwordEncoder = passwordEncoder;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    /**
     * Controller → Service → Repository → DB 순서로 호출됩니다.
     * 이 메서드 전체가 한 트랜잭션입니다. 회원 또는 동의 저장 중 예외가 나면 모두 취소됩니다.
     */
    // 트랜잭션은 여러 DB 작업을 하나로 묶는 단위입니다.
    // 정상 종료하면 커밋(확정), RuntimeException이 밖으로 전달되면 롤백(취소)합니다.
    // BusinessException과 여기서 발생하는 Spring DB 예외는 RuntimeException에 해당합니다.
    @Transactional
    public Result signup(String name, String email, String password, String requestedTerms, String requestedPrivacy) {
        // 1. 문서 길이는 요청 DTO에서, 실제 허용된 버전인지는 여기서 확인합니다.
        // isBlank(): 빈 문자열 또는 공백뿐인지 검사. equals(): 문자열 내용 비교.
        // ||는 '하나라도 참이면', !는 '참/거짓을 반대로'라는 뜻입니다.
        if (termsVersion.isBlank() || privacyVersion.isBlank()
                || !termsVersion.equals(requestedTerms) || !privacyVersion.equals(requestedPrivacy)) {
            // throw를 만나면 정상 흐름을 중단합니다. 공통 예외 처리기가 이 코드를 400 응답으로 바꿉니다.
            throw new BusinessException(ErrorCode.TERMS_VERSION_INVALID);
        }
        // 2. 앞뒤 공백 제거(strip) 후 소문자로 통일합니다.
        // Locale.ROOT는 서버의 언어 설정과 관계없이 동일한 소문자 변환 규칙을 쓰게 합니다.
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        // 메서드 이름을 해석해 JPA가 이메일 존재 여부를 조회합니다. 존재하면 true입니다.
        if (users.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 3. encode()로 비밀번호를 BCrypt 해시로 바꾼 뒤 기존 생성 메서드에 전달합니다.
        // User.create()는 Java 객체만 만듭니다. DB 저장은 다음 Repository 호출에서 진행됩니다.
        User user = User.create(normalizedEmail, passwordEncoder.encode(password), name.strip());
        // try 안의 저장 작업에서 지정한 예외가 발생하면 catch 블록으로 이동합니다.
        try {
            // flush는 SQL을 지금 실행하는 것이며 커밋은 아닙니다. 이후 동의 저장 실패 시 함께 롤백됩니다.
            // 기존 User의 @PrePersist가 생성 시각을 채우고 JPA가 UUID를 생성합니다.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청은 사전 중복 검사를 모두 통과할 수 있어 DB의 유일성 제약도 처리합니다.
            // 이 코드는 users 삽입의 unique 위반을 이메일 중복으로 간주합니다.
            // 23505 자체는 특정 컬럼이 아닌 유일성 위반 코드이므로 PK 충돌도 같은 분류가 됩니다.
            // 예외는 여러 겹으로 감싸질 수 있어 getCause()로 내부 원인을 하나씩 확인합니다.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                // instanceof는 타입 검사입니다. SQLException이면 sql 변수로 SQL 상태를 읽습니다.
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
                }
            }
            // 유일성 위반이 아니면 원래 오류를 다시 던집니다. 성공으로 숨기지 않고 롤백합니다.
            throw e;
        }
        // 4. 방금 저장한 동일한 user에 이용약관과 개인정보 동의를 각각 연결합니다.
        // ConsentType은 동의 종류를 구분하는 enum(정해진 값 목록)입니다.
        // 기존 create()가 동의 시각을 채웁니다. user 참조는 DB의 user_id 외래 키로 저장됩니다.
        consents.save(UserConsent.create(user, UserConsent.ConsentType.TERMS, requestedTerms));
        consents.save(UserConsent.create(user, UserConsent.ConsentType.PRIVACY, requestedPrivacy));
        // save()는 INSERT를 미룰 수 있습니다. flush()로 대기 중인 SQL을 실행해 저장 오류를 확인합니다.
        consents.flush();
        // Spring이 커밋을 성공시켜야 Controller가 이 결과를 받아 201을 반환합니다.
        return new Result(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }

    // record는 결과 데이터를 간단히 담는 타입입니다. 생성자와 userId(), name() 같은 접근자가 생깁니다.
    // Service는 HTTP 응답 대신 이 결과를 반환하고, Controller가 SignupResponse로 옮깁니다.
    // UUID는 회원 식별자, OffsetDateTime은 UTC 오프셋을 포함한 날짜와 시각입니다.
    public record Result(UUID userId, String name, String email, OffsetDateTime createdAt) { }
}
