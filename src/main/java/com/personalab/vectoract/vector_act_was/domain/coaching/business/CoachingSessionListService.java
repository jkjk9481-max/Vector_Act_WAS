package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.CoachingSessionListResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * D01 연습 기록 목록. 본인의 실시간 코칭 세션만 대상이며, 취소(CANCELED)·삭제된 세션과
 * 업로드 영상 분석(별도 도메인·테이블)은 제외합니다(API 명세서 D01).
 */
@Service
public class CoachingSessionListService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    // from/to 중 한쪽만 있으면 30일 범위(from~from+29, 또는 to-29~to)로 보완합니다.
    private static final long SUPPLEMENT_DAYS = 29;
    // from/to 모두 있으면 최대 366일 범위까지 허용합니다(ChronoUnit.DAYS.between은 날짜 수 - 1을 반환).
    private static final long MAX_RANGE_DAYS = 365;

    private final CoachingSessionRepository sessions;
    private final AnalysisRepository analyses;

    public CoachingSessionListService(CoachingSessionRepository sessions, AnalysisRepository analyses) {
        this.sessions = sessions;
        this.analyses = analyses;
    }

    @Transactional(readOnly = true)
    public CoachingSessionListResponse list(UUID userId, int page, int size, String sort, LocalDate from,
                                            LocalDate to) {
        if (page < 0) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (size < 1 || size > MAX_SIZE) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        Sort.Direction direction = parseSort(sort);
        var range = resolveRange(from, to);

        var pageable = PageRequest.of(page, size, Sort.by(direction, "createdAt"));
        var result = sessions.findForList(userId, range.from(), range.to(), CoachingSession.Status.CANCELED,
                pageable);

        var scoreBySession = loadScores(result.getContent());
        var items = result.getContent().stream()
                .map(s -> toItem(s, scoreBySession.get(s.getId())))
                .toList();

        long totalElements = result.getTotalElements();
        // Spring의 Page.getTotalPages()는 0건이어도 1을 돌려주므로, 명세대로 0건이면 0이 되게 직접 계산합니다.
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new CoachingSessionListResponse(items, page, size, totalElements, totalPages, result.hasNext());
    }

    private static Sort.Direction parseSort(String sort) {
        return switch (sort) {
            case "createdAt,desc" -> Sort.Direction.DESC;
            case "createdAt,asc" -> Sort.Direction.ASC;
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        };
    }

    /** from/to(KST 날짜, 포함) 쿼리를 createdAt 비교용 OffsetDateTime 범위([from 0시, to+1일 0시))로 바꿉니다. */
    private static Range resolveRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) return new Range(null, null);

        LocalDate rangeFrom = from != null ? from : to.minusDays(SUPPLEMENT_DAYS);
        LocalDate rangeTo = to != null ? to : from.plusDays(SUPPLEMENT_DAYS);
        if (rangeTo.isBefore(rangeFrom)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (ChronoUnit.DAYS.between(rangeFrom, rangeTo) > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return new Range(rangeFrom.atStartOfDay(KST).toOffsetDateTime(),
                rangeTo.plusDays(1).atStartOfDay(KST).toOffsetDateTime());
    }

    private record Range(OffsetDateTime from, OffsetDateTime to) {}

    private Map<UUID, BigDecimal> loadScores(List<CoachingSession> items) {
        if (items.isEmpty()) return Map.of();
        var sessionIds = items.stream().map(CoachingSession::getId).toList();
        var scoreBySession = new HashMap<UUID, BigDecimal>();
        for (Analysis analysis : analyses.findBySessionIdIn(sessionIds)) {
            scoreBySession.put(analysis.getSessionId(), oneDecimal(analysis.getOverallScore()));
        }
        return scoreBySession;
    }

    private static CoachingSessionListResponse.Item toItem(CoachingSession s, BigDecimal overallScore) {
        return new CoachingSessionListResponse.Item(s.getId(), s.getCreatedAt(), s.getDurationMs(),
                s.getStatus().name(), s.getVideoStatus().name(), s.getAnalysisStatus().name(), overallScore);
    }

    // 점수는 소수 1자리로 반올림해 응답합니다(C10과 같은 규칙. DB는 소수 2자리까지 저장).
    private static BigDecimal oneDecimal(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }
}
