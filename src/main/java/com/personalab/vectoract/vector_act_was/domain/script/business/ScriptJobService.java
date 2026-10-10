package com.personalab.vectoract.vector_act_was.domain.script.business;

import com.personalab.vectoract.vector_act_was.domain.script.persistence.ScriptJob;
import com.personalab.vectoract.vector_act_was.domain.script.persistence.ScriptJobRepository;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * S01 접수, S02 조회, OCR 상태 전이, 만료 정리의 업무 규칙을 담당합니다.
 * 저장소 객체는 DB 트랜잭션으로 되돌릴 수 없어 순서를 다음과 같이 둡니다.
 * 이미지 저장 → 작업 저장 → 커밋 후 처리 시작, 롤백 시 저장한 이미지 삭제.
 */
@Service
public class ScriptJobService {
    private final ScriptJobRepository jobs;
    private final ScriptImageStorage storage;
    private final ScriptImageInspector inspector;
    private final ApplicationEventPublisher events;

    public ScriptJobService(ScriptJobRepository jobs, ScriptImageStorage storage,
                            ScriptImageInspector inspector, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.storage = storage;
        this.inspector = inspector;
        this.events = events;
    }

    /** S01·S02 응답에 필요한 값만 담습니다. 원본 이미지 위치는 포함하지 않습니다. */
    public record View(UUID jobId, String status, String content, String failureCode, OffsetDateTime expiresAt) {
        static View of(ScriptJob job) {
            return new View(job.getId(), job.getStatus().name(), job.getResultContent(),
                    job.getFailureCode(), job.getExpiresAt());
        }
    }

    @Transactional
    public View submit(UUID userId, byte[] content) {
        inspector.inspect(content);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // 회원 ID를 key에 포함해 소유자를 추적하고, UUID로 key 충돌을 피합니다.
        String key = "script-ocr/" + userId + "/" + UUID.randomUUID();
        storage.put(key, content);
        // 이후 단계에서 실패해 롤백되면 방금 저장한 이미지를 삭제합니다.
        events.publishEvent(new ScriptImageStored(key));
        var job = jobs.saveAndFlush(ScriptJob.createOcr(userId, key, now));
        events.publishEvent(new ScriptJobSubmitted(job.getId()));
        return View.of(job);
    }

    @Transactional(readOnly = true)
    public View get(UUID userId, UUID jobId) {
        // 다른 회원의 작업은 없는 작업과 같은 404로 응답해 존재 여부를 숨깁니다.
        var job = jobs.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!job.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BusinessException(ErrorCode.RESULT_EXPIRED);
        }
        return View.of(job);
    }

    /**
     * QUEUED 작업을 PROCESSING으로 바꾸고 원본 이미지 key를 반환합니다. 처리할 수 없으면 null입니다.
     * 커밋 후 이벤트 안에서 호출되므로 새 트랜잭션을 열어야 변경이 확정됩니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String start(UUID jobId) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var job = jobs.lockById(jobId).orElse(null);
        if (job == null || job.getStatus() != ScriptJob.Status.QUEUED || !job.getExpiresAt().isAfter(now)) {
            return null;
        }
        job.markProcessing(now);
        return job.getSourceObjectKey();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, String content) {
        var job = jobs.lockById(jobId).orElse(null);
        if (job == null || job.getStatus() != ScriptJob.Status.PROCESSING) return;
        job.complete(content, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String failureCode) {
        var job = jobs.lockById(jobId).orElse(null);
        if (job == null || job.getStatus() == ScriptJob.Status.COMPLETED
                || job.getStatus() == ScriptJob.Status.FAILED) return;
        job.fail(failureCode, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** 원본 이미지를 저장소에서 지운 뒤 호출해 key를 비웁니다. 지우지 못했다면 만료 정리가 다시 시도합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearSource(UUID jobId) {
        jobs.lockById(jobId).ifPresent(ScriptJob::clearSource);
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiredIds() {
        return jobs.findExpiredIds(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 만료된 작업 하나를 정리합니다. 저장소 삭제가 실패하면 예외가 전파되어 행이 남고 다음 주기에 재시도합니다.
     * 작업마다 별도 트랜잭션이어야 하므로 Scheduler가 이 메서드를 개별 호출합니다.
     */
    @Transactional
    public boolean purge(UUID jobId) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var job = jobs.lockById(jobId).orElse(null);
        if (job == null || job.getExpiresAt().isAfter(now)) return false;
        if (job.getSourceObjectKey() != null) storage.delete(job.getSourceObjectKey());
        jobs.delete(job);
        jobs.flush();
        return true;
    }
}
