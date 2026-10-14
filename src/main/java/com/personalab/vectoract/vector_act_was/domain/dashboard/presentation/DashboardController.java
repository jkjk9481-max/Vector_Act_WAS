package com.personalab.vectoract.vector_act_was.domain.dashboard.presentation;

import com.personalab.vectoract.vector_act_was.domain.dashboard.business.DashboardPeriodService;
import com.personalab.vectoract.vector_act_was.domain.dashboard.business.DashboardWeaknessService;
import com.personalab.vectoract.vector_act_was.domain.dashboard.presentation.dto.DashboardPeriodResponse;
import com.personalab.vectoract.vector_act_was.domain.dashboard.presentation.dto.DashboardWeaknessResponse;
import com.personalab.vectoract.vector_act_was.global.common.response.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 대시보드 API(D05 주간 분석, D06 월간 분석, D07 누적 취약점)의 HTTP 계층입니다.
 * 읽기 전용 GET이라 CSRF 대상이 아니며 Bearer 인증만 사용합니다.
 */
@RestController
public class DashboardController {
    private final DashboardPeriodService periods;
    private final DashboardWeaknessService weaknesses;

    public DashboardController(DashboardPeriodService periods, DashboardWeaknessService weaknesses) {
        this.periods = periods;
        this.weaknesses = weaknesses;
    }

    /** date는 KST 날짜(yyyy-MM-dd)이며, 생략 시 KST 기준 오늘이 속한 주입니다. */
    @GetMapping("/api/dashboard/weekly")
    public ResponseEntity<ApiResponse<DashboardPeriodResponse>> weekly(@AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(periods.getWeekly(userId, date)));
    }

    /** month는 KST 월(yyyy-MM)이며, 생략 시 KST 기준 이번 달입니다. */
    @GetMapping("/api/dashboard/monthly")
    public ResponseEntity<ApiResponse<DashboardPeriodResponse>> monthly(@AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(periods.getMonthly(userId, parseMonth(month))));
    }

    /** D07 누적 취약점. Query 없음. */
    @GetMapping("/api/dashboard/weaknesses")
    public ResponseEntity<ApiResponse<DashboardWeaknessResponse>> weaknesses(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(weaknesses.getWeaknesses(userId)));
    }

    /** month가 YYYY-MM 형식이 아니면 400입니다(경로 변수가 아니라 직접 파싱하므로 지역 핸들러를 거치지 않습니다). */
    private static YearMonth parseMonth(String value) {
        if (value == null) return null;
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    // ===== 지역 예외 처리 =====

    /** date 형식이 아니면 400입니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidInput(Exception ignored) { return error(ErrorCode.VALIDATION_ERROR); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> businessError(BusinessException exception) { return error(exception.getErrorCode()); }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(Exception ignored) { return error(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ignored) { return error(ErrorCode.INTERNAL_ERROR); }

    private ResponseEntity<ErrorResponse> error(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }
}
