# U07 업로드 분석 상태 조회

`GET /api/upload-analyses/{analysisId}` · 성공 200 · Bearer

업로드 영상 분석의 현재 상태를 조회합니다. 구현 파일은 U06 문서와 같습니다.

## 처리 순서
1. analysisId가 본인 소유의 UPLOADED_VIDEO 분석이 아니면 404(타인 소유·없는·LIVE_CAPTURE 분석 모두 같은 404)
2. 결과 보관 기한(종료 상태 진입 후 24시간)이 지났으면 `410 RESULT_EXPIRED`
3. 그 외에는 현재 상태(QUEUED/PROCESSING/COMPLETED/PARTIAL/FAILED/CANCELED)를 그대로 응답

## 테스트
`UploadAnalysisStatusIntegrationTests`: QUEUED/COMPLETED/FAILED/CANCELED 상태 조회, 만료된 결과(410), 타인·없는·LIVE_CAPTURE 분석 404, Bearer 필수.
