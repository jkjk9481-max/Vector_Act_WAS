# U04 업로드 상태 조회

`GET /api/video-uploads/{uploadId}` · 성공 200 · Bearer

업로드의 현재 상태를 읽기 전용으로 조회합니다. 구현 파일은 U01 문서와 같습니다.

## 처리 순서
1. 소유·존재(404)
2. 상태가 INITIATED/UPLOADING/VERIFYING(아직 종료되지 않음)인데 만료 시각이 지났으면 `410 UPLOAD_EXPIRED`
3. 그 외에는 현재 상태를 그대로 응답(READY/FAILED/CANCELED는 만료와 무관하게 항상 조회 가능)

## 명세에 없거나 정한 값 (확인 필요)
- GET은 부작용이 없어야 하므로, 만료가 확인되어도 DB의 status를 EXPIRED로 바꾸지 않고 오류만 반환합니다(C09 세션 상태 조회가 세션을 바꾸지 않는 것과 같은 원칙).

## 테스트
`UploadStatusIntegrationTests`: 각 상태(INITIATED/UPLOADING/READY/FAILED/CANCELED) 조회, 만료된 미완료 업로드(410), 타인·없는 업로드 404, Bearer 필수.
