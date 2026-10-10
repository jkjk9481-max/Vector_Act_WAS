package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 촬영 종료(C07)·취소(C08)·상태 조회(C09)가 함께 쓰는 세션 진행 상태 응답입니다.
 *
 * @param sessionId           세션 ID
 * @param status              세션 상태(CREATED/RECORDING/FINALIZING/COMPLETED/FAILED/CANCELED)
 * @param videoStatus         영상 상태
 * @param analysisStatus      분석 상태
 * @param analysisMode        현재 분석 실행 모드
 * @param missingChunkIndexes 종료 선언 후 아직 검증되지 않은 청크 번호. 종료 선언 전에는 빈 배열
 * @param uploadDeadlineAt    누락 청크 업로드 마감(종료 최초 접수 + 15분). 종료 전에는 null
 * @param analysisAttempt     분석 시도 횟수. 아직 요청 전이면 0
 * @param failureCode         실패 코드. 없으면 null
 */
public record SessionProgressResponse(UUID sessionId, String status, String videoStatus, String analysisStatus,
                                      String analysisMode, List<Integer> missingChunkIndexes,
                                      OffsetDateTime uploadDeadlineAt, int analysisAttempt, String failureCode) {}
