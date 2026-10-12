# U02 Part 업로드 URL 발급

`POST /api/video-uploads/{uploadId}/parts/{partNumber}/upload-url` · 성공 200 · Bearer

Part 하나를 올릴 자리를 예약하고 10분 유효한 임시 업로드 URL(Presigned UploadPart)을 발급합니다.
구현 파일은 U01 문서(`docs/U01-video-upload-start.md`)와 같습니다.

## 처리 순서
1. 소유·존재(404, 타인 소유·없는 uploadId는 구분하지 않는 같은 404)
2. partNumber가 1~partCount 범위 밖이면 `400 VALIDATION_ERROR`
3. 업로드 상태가 INITIATED/UPLOADING이 아니면 `409 UPLOAD_STATE_CONFLICT`; 만료 시각이 지났으면 `410 UPLOAD_EXPIRED`
4. 요청 sizeBytes가 해당 Part의 정해진 크기(마지막만 나머지)와 다르면 `400 VALIDATION_ERROR`
5. 같은 번호의 기존 Part 확인
   - 없음: 새로 예약(RESERVED)
   - 같은 내용(크기·해시): URL만 재발급(재시도 허용)
   - 다른 내용: `409 PART_CONFLICT`
6. 업로드를 UPLOADING으로 전환하고(최초 1회) S3 UploadPart Presigned URL 발급

## 명세에 없거나 정한 값 (확인 필요)
- **Part 크기 검증**: "정해진 part 크기, 마지막만 나머지"를 서버가 직접 계산해 선언값과 대조했습니다. 명세에 오류 코드가 없어 `VALIDATION_ERROR`(400)로 처리했습니다.
- **만료 판단**: `video_uploads.status`에 EXPIRED 값이 있지만 정리 스케줄러가 없어(C04와 동일한 결정) `expires_at`을 직접 비교해 판단하며 DB에는 저장하지 않습니다.
- **requiredHeaders**: C04/S3VideoStorage와 같은 기준으로 `host`, `content-length`는 제외했습니다.

## 테스트
`PartUploadUrlIntegrationTests`: 정상 예약과 DB 상태, 같은 내용 재발급, 다른 내용 충돌, Part 크기 불일치(400), partNumber 범위(400), 업로드 상태 충돌(409), 만료(410), 타인·없는 업로드 404, Bearer 필수.
