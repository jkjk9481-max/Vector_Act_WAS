package com.personalab.vectoract.vector_act_was.domain.upload.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface VideoUploadRepository extends JpaRepository<VideoUpload, UUID> {
    /** 타인 소유 업로드 ID는 존재 여부를 알 수 없도록 같은 404로 처리하기 위해 소유자 조건을 함께 둡니다. */
    Optional<VideoUpload> findByIdAndUserId(UUID id, UUID userId);
}
