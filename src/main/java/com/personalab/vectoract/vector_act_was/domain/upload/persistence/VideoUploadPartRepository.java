package com.personalab.vectoract.vector_act_was.domain.upload.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoUploadPartRepository extends JpaRepository<VideoUploadPart, UUID> {
    Optional<VideoUploadPart> findByUploadIdAndPartNumber(UUID uploadId, int partNumber);

    List<VideoUploadPart> findByUploadIdOrderByPartNumberAsc(UUID uploadId);
}
