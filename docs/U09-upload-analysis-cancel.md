# U09 업로드 분석 취소

`POST /api/upload-analyses/{analysisId}/cancel` · 성공 202 · Bearer

QUEUED/PROCESSING 상태의 업로드 영상 분석만 취소합니다. 구현 파일은 U06 문서와 같습니다.

## 처리 순서
1. analysisId가 본인 소유의 UPLOADED_VIDEO 분석이 아니면 404
2. 결과 보관 기한이 지났으면 `410 RESULT_EXPIRED`
3. QUEUED/PROCESSING이면 CANCELED로 전환하고 24시간 만료 시각을 둠
4. 이미 CANCELED면 상태를 바꾸지 않고 같은 결과를 응답(반복 취소 허용)
5. COMPLETED/PARTIAL/FAILED면 `409 ANALYSIS_STATE_CONFLICT`

## 테스트
`UploadAnalysisCancelIntegrationTests`: QUEUED/PROCESSING 취소와 DB 상태, 반복 취소(같은 결과), 종료 상태 거부(409 ANALYSIS_STATE_CONFLICT), 만료(410), 타인·없는·LIVE_CAPTURE 분석 404, Bearer 필수.
