# U01 대용량 업로드 시작

`POST /api/video-uploads` · 성공 201 · Bearer + Idempotency-Key

기존 영상 파일을 S3 Multipart Upload로 올리기 위한 업로드 자원을 만듭니다.
흐름: **U01(이 문서)** → U02(Part URL 발급, 반복) → U03(완료·검증 접수) → U04/U05(조회·취소).

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/VideoUploadController` | U01~U05 엔드포인트, 지역 예외 처리 |
| `business/VideoUploadService` | 입력 검증, 멱등 처리, S3 Multipart Upload 시작 |
| `business/VideoUploadStorage` / `InMemoryVideoUploadStorage` / `S3VideoUploadStorage` | Multipart Upload 계약과 구현(버킷 설정 시 S3) |
| `persistence/VideoUpload(Repository)` | `video_uploads` 엔티티(DB 설계서 3.9) |
| `dto/VideoUploadCreateRequest`, `VideoUploadResponse` | 요청·응답(U01/U03/U04/U05 공용 응답) |
| `ErrorCode` | `PART_CONFLICT`/`UPLOAD_STATE_CONFLICT`/`PARTS_MISSING`/`UPLOAD_IN_USE`/`UPLOAD_NOT_READY`/`ANALYSIS_STATE_CONFLICT` 추가(U 시리즈 공용) |
| `SignupSecurityConfig` | U01~U05 엔드포인트를 CSRF 제외(Bearer 전용) |
| `docs/sql/U01_video_uploads.sql` | 운영 DB 적용용 DDL. `analyses.upload_id`의 FK도 이 스크립트에서 추가 |

## 처리 순서
1. 입력 검증 → `400 VALIDATION_ERROR` (fileName 1~255자)
2. contentType이 `video/mp4`/`video/webm`이 아니면 `415 UNSUPPORTED_MEDIA_TYPE`
3. sizeBytes < 1 → `400`, sizeBytes > 2147483648(2GiB) → `413 FILE_TOO_LARGE`
4. 회원 행 잠금 → 멱등 기록 확인(같은 키·같은 본문 재시도는 처음 응답 복원, 다른 본문은 `409 IDEMPOTENCY_CONFLICT`)
5. S3 CreateMultipartUpload로 `s3UploadId` 발급 → `video_uploads` 행 저장(INITIATED, partCount = ceil(sizeBytes/16MiB))

## 명세에 없거나 정한 값 (확인 필요)
- **object_key 규칙**: `video-uploads/{userId}/{UUID}` (ChunkUploadService의 staging key 규칙과 동일한 발상).
- **partCount 계산**: `ceil(sizeBytes / 16777216)`. 명세는 공식을 문서 레이아웃이 깨져 직접 명시하지 않지만 DB 설계서 part_count 설명과 일치합니다.
- **Content-Type 비교**: 대소문자 무시, 앞뒤 공백 제거 후 정확히 `video/mp4` 또는 `video/webm`과 일치해야 합니다(C02처럼 codecs 파라미터는 허용하지 않음).

## 테스트
`VideoUploadCreateIntegrationTests`: 정상 생성과 DB 상태, 같은 키 재시도 복원, 다른 본문 충돌(409), 2GiB 초과(413), 미지원 Content-Type(415), 필수값 누락(400), Bearer 필수.
