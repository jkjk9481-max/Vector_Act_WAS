package com.personalab.vectoract.vector_act_was.global.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 버킷이 설정된 경우에만 S3 클라이언트를 만듭니다. 설정이 없으면 임시 메모리 저장소가 사용됩니다.
 * 자격 증명은 AWS 기본 체인(AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 환경변수 등)으로 읽습니다.
 *
 * <p>클래스 전체에 조건을 걸어, 버킷이 없는 환경(로컬·테스트)에서는 AWS 클라이언트가 아예 만들어지지 않습니다.
 * 따라서 AWS 자격 증명이 없어도 애플리케이션이 정상 기동합니다.
 */
@Configuration
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(S3StorageConfig.class);

    // destroyMethod = "close": 애플리케이션 종료 시 클라이언트의 커넥션·스레드 자원을 정리합니다.
    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${storage.s3.region}") String region) {
        log.info("object_storage type=S3 region={}", region);
        // 자격 증명을 코드에 쓰지 않습니다. 빌더가 기본 자격 증명 체인에서 알아서 찾습니다.
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(@Value("${storage.s3.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
