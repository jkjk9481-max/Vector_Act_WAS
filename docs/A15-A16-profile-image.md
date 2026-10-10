# A15·A16 프로필 이미지 등록·변경·삭제

| API | 설명 | 인증 |
| --- | --- | --- |
| `PUT /api/users/me/profile-image` | multipart `file` 필드로 등록·교체 | Bearer (CSRF 제외) |
| `DELETE /api/users/me/profile-image` | 삭제. 이미지가 없어도 200 | Bearer (CSRF 제외) |

응답은 A06과 동일한 회원 정보이며 `profileImageUrl`은 5분 유효 Presigned GET URL, 이미지가 없으면 `null`입니다.
이에 맞춰 **A06·A07 응답에도 `profileImageUrl`을 추가**했습니다(최종 API 명세 기준).

## 오류

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | `file` 파트 누락, 빈 파일, 25메가픽셀 초과 이미지 |
| 413 | FILE_TOO_LARGE | 5MiB 초과 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 디코딩 결과가 JPEG/PNG가 아님, 손상 파일, multipart가 아닌 요청 |
| 429 | RATE_LIMITED | 회원별 기본 60초 10회 초과 |
| 404 | RESOURCE_NOT_FOUND | 없는 회원, 탈퇴 회원 |

## 처리 순서 (A15)

```text
ProfileImageController   크기·빈 파일 검사, 회원별 요청 제한
  → ProfileImageService  (하나의 트랜잭션)
      1. ProfileImageProcessor: 실제 디코딩으로 JPEG/PNG 검증 후 재인코딩(EXIF 제거)
      2. ProfileImageStorage.put(새 key)             ← DB 트랜잭션으로 되돌릴 수 없음
      3. 회원 행 잠금 → ACTIVE 확인 → profile_image_key 변경
  → 커밋 후   ProfileImageCleanupListener: 이전 객체 삭제
  → 롤백 시   ProfileImageCleanupListener: 방금 저장한 새 객체 삭제
```

- 확장자·Content-Type 헤더는 신뢰하지 않고 실제 내용으로 판별합니다. 헤더가 jpeg여도 내용이 PNG면 PNG로 저장합니다.
- key는 `profile-images/{userId}/{uuid}.{png|jpg}`이며 DB에는 key만 저장하고 URL은 저장하지 않습니다.
- JPEG는 품질 0.9로 재인코딩합니다(손실 압축이므로 원본과 바이트가 다릅니다).

## 저장소와 한계

- `ProfileImageStorage`는 계약입니다. 현재 구현은 `InMemoryProfileImageStorage`(임시)이며 **재시작 시 이미지가 사라지고
  반환 URL은 실제로 열리지 않습니다.** 운영 배포 전에 S3 구현체로 교체해야 합니다.
- 이전 객체 삭제가 실패하면 요청은 성공 처리하고 key와 예외 종류만 로그에 남깁니다. DB설계서의 "실패 시 재처리"를
  위한 영속 큐·자동 재시도는 없습니다.
- 회원 Hard Delete(A10) 시 프로필 이미지 객체 삭제는 아직 연결되어 있지 않습니다. 실제 저장소 구현 시
  `MemberDataEraser.deleteExternalData`에서 삭제해야 합니다.
- EXIF Orientation을 제거하므로, 회전 정보에만 의존하던 사진은 표시 방향이 달라질 수 있습니다.
- 요청 제한 횟수, 이미지 25메가픽셀 상한은 명세에 없어 정한 기본값입니다.

## 설정과 DB

- `PROFILE_IMAGE_RATE_LIMIT_MAX_ATTEMPTS`(10), `PROFILE_IMAGE_RATE_LIMIT_WINDOW_SECONDS`(60)
- `spring.servlet.multipart.max-file-size=10MB`: 컨테이너 상한이며 API별 5MiB는 Controller가 검사합니다.
- 운영 DB에는 배포 전에 `docs/sql/A15_profile_image.sql`을 적용합니다(자동 실행 없음, 이번 작업에서 실행하지 않음).

## 테스트

`ProfileImageIntegrationTests`: PNG/JPEG 저장, EXIF 제거, 내용 기반 판별, 교체 시 이전 객체 삭제, 삭제·재삭제,
형식·크기·누락 오류, Bearer 필수/CSRF 불필요, 탈퇴 회원 및 롤백 정리, 저장소 실패, 정리 실패, 회원별 요청 제한.
