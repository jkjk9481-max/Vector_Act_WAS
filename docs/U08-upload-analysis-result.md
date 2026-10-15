# U08 업로드 분석 결과 조회

`GET /api/upload-analyses/{analysisId}/result` · 성공 200 · Bearer

완료(COMPLETED) 또는 부분 완료(PARTIAL)된 업로드 영상 분석의 결과를 조회합니다.
응답 형식이 C10(`AnalysisResultResponse`)과 완전히 같아 `AnalysisResultService#getForUpload`가 매핑 로직을 재사용합니다.
`sessionId`는 업로드 분석에서는 항상 `null`입니다.

## 처리 순서
1. analysisId가 본인 소유의 UPLOADED_VIDEO 분석이 아니면 404
2. 결과 보관 기한이 지났으면 `410 RESULT_EXPIRED`
3. 분석이 실패했으면 `409 ANALYSIS_FAILED`, 아직 COMPLETED/PARTIAL이 아니면 `409 RESULT_NOT_READY`
4. 점수(7개 항목)·시간순 피드백(최대 300개)·강점·개선점·다음 연습 추천을 C10과 같은 형식으로 응답

## 명세에 없거나 정한 값 (확인 필요)
- RESULT_EXPIRED 검사를 C10(실시간) 경로에도 공통으로 추가했지만, LIVE_CAPTURE 분석은 `expires_at`이 항상 null이라 실제로는 영향이 없습니다.

## 테스트
`UploadAnalysisResultIntegrationTests`: 완료된 결과의 7개 점수 항목·피드백 시간순 확인, PARTIAL 허용, FAILED(409 ANALYSIS_FAILED), QUEUED(409 RESULT_NOT_READY), 만료(410), 타인·없는·LIVE_CAPTURE 분석 404, Bearer 필수.
