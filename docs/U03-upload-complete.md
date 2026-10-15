# U03 업로드 완료·검증 접수

`POST /api/video-uploads/{uploadId}/complete` · 성공 202 · Bearer

모든 Part를 순서대로 제출해 S3 Multipart Upload를 조립하고 업로드를 READY로 확정합니다.
구현 파일은 U01 문서(`docs/U01-video-upload-start.md`)와 같습니다.

## 처리 순서
1. 소유·존재(404)
2. 업로드 상태가 INITIATED/UPLOADING이 아니면 `409 UPLOAD_STATE_CONFLICT`; 만료되었으면 `410 UPLOAD_EXPIRED`
3. parts 개수가 partCount와 다르거나 1부터 오름차순이 아니면 `400 VALIDATION_ERROR`
4. 각 Part가 U02로 예약된 적이 없으면 `409 PARTS_MISSING`; 제출한 sha256이 예약 때와 다르면 `409 CHECKSUM_MISMATCH`
5. 상태를 VERIFYING으로 바꾸고 S3 CompleteMultipartUpload 호출 → Part들을 UPLOADED로 확정 → 업로드를 READY로 확정

## 명세에 없거나 정한 값 (확인 필요)
- **duration_ms**: 영상 길이를 재는 미디어 프로브 단계가 아직 없어 READY로 확정하면서도 `null`로 둡니다. 실제 값은 AI 서버 연동(분석 단계)에서 채울 자리입니다. API 응답 자체에는 durationMs 필드가 없습니다(U01/U03~U05 응답은 uploadId/status/partSizeBytes/partCount/expiresAt/failureCode 6개 필드만 명시되어 있습니다).
- **ETag 검증**: S3 CompleteMultipartUpload가 ETag 불일치를 자체적으로 거부합니다. 애플리케이션은 제출된 sha256만 U02 예약 시점 값과 대조합니다.
- **완료 후 재호출**: 이미 READY/FAILED/CANCELED인 업로드에 다시 complete를 호출하면 `409 UPLOAD_STATE_CONFLICT`입니다(멱등 복원은 하지 않음 — 명세에 Idempotency-Key가 없는 API입니다).

## 테스트
`UploadCompleteIntegrationTests`: 정상 완료와 DB 상태(READY), 누락 Part(409 PARTS_MISSING), 해시 불일치(409 CHECKSUM_MISMATCH), parts 순서·개수 오류(400), 업로드 상태 충돌(409), 만료(410), 타인·없는 업로드 404, Bearer 필수.
