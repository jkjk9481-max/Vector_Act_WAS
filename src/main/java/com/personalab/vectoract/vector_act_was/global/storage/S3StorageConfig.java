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
 */
@Configuration
@ConditionalOnExpression("!'${storage.s3.bucket:}'.isEmpty()")
public class S3StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(S3StorageConfig.class);

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${storage.s3.region}") String region) {
        log.info("object_storage type=S3 region={}", region);
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(@Value("${storage.s3.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
