# U05 업로드 취소

`DELETE /api/video-uploads/{uploadId}` · 성공 202 · Bearer

분석에 접수되기 전의 업로드를 취소합니다. 구현 파일은 U01 문서와 같습니다.

## 처리 순서
1. 소유·존재(404)
2. 이 업로드를 가리키는 분석(`analyses.upload_id`)이 있으면 `409 UPLOAD_IN_USE`
3. 이미 CANCELED면 상태를 바꾸지 않고 그대로 응답(반복 취소 허용)
4. 그 외 상태면 S3 AbortMultipartUpload 호출 후 CANCELED로 전환

## 명세에 없거나 정한 값 (확인 필요)
- **"분석 접수 전만 취소 가능"의 판단 기준**: `video_uploads.status`가 아니라 `analyses.upload_id` 존재 여부로 판단했습니다. READY 업로드도 아직 U06으로 분석을 접수하지 않았다면 취소할 수 있습니다.
- **만료된 업로드 취소**: U05 오류 목록에 410이 없어 만료 여부와 무관하게 취소를 허용합니다.
- **AbortMultipartUpload 실패**: S3에 없는 Multipart Upload(이미 완료·만료)를 취소해도 성공으로 처리합니다(재시도 안전).

## 테스트
`UploadCancelIntegrationTests`: 정상 취소와 DB 상태, 반복 취소(같은 결과), 분석 접수된 업로드 취소 거부(409 UPLOAD_IN_USE), 타인·없는 업로드 404, Bearer 필수.
