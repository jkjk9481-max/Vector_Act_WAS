# D03 영상 재생 URL 발급

`GET /api/coaching-sessions/{sessionId}/video` · 성공 200 · Bearer · 본문 없음

세션에 저장된 원본 영상을 재생할 수 있는 5분 유효 Presigned GET URL을 발급하는 읽기 전용 API입니다. 버킷이 비공개라 재생하려면 이 URL이 필요합니다(A15/A16 프로필 이미지와 같은 Presigned GET 패턴).

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/CoachingSessionController.video` | `/video` GET 엔드포인트 추가 |
| `business/SessionVideoAccessService` (신규) | 상태 검사(READY 여부, 만료) 후 Presigned URL 발급 |
| `business/VideoStorage.presignGet` (인터페이스에 추가) | 읽기 전용 임시 URL 계약 |
| `business/S3VideoStorage.presignGet` | 운영 구현(`S3Presigner.presignGetObject`) |
| `business/InMemoryVideoStorage.presignGet` | 로컬·테스트용 가짜 URL(`.invalid` 도메인) |
| `presentation/dto/SessionVideoUrlResponse` (신규) | `url`, `expiresAt`, `contentType` 응답 |

## 동작
- 세션의 `videoStatus`가 `READY`일 때만 URL을 발급합니다. 그 전 상태(NOT_STARTED/UPLOADING/ASSEMBLING/FAILED)는 `409 VIDEO_NOT_READY`.
- `videoStatus`가 `EXPIRED` 또는 `DELETED`(30일 경과 후 원본 삭제)이면 `410 VIDEO_EXPIRED`. 분석 결과는 이 상태와 무관하게 유지됩니다.
- URL은 `session_videos.object_key`를 대상으로 서명하며, TTL은 5분입니다. `contentType`은 저장된 `stored_content_type`이며 없으면 기본값 `video/mp4`.
- 타인 소유·삭제된·없는 세션은 `404 RESOURCE_NOT_FOUND`, 경로의 `sessionId`가 UUID가 아니면 `400`.

## 구현 시 참고
- `videoStatus`를 `READY`/`EXPIRED`/`DELETED`로 바꾸는 조립·삭제 단계(비동기 처리·스케줄러)는 아직 구현되어 있지 않습니다(C07 이후 분석·조립 파이프라인과 S3 수명 관리는 이 PR의 범위 밖). 그래서 이번 통합 테스트는 `AnalysisRetryService`의 기존 관례처럼 `videoStatus`를 직접 DB에 써서 각 상태를 흉내 냈습니다. 실제 조립 단계가 생기면 이 서비스의 판단 로직(상태값 우선)은 그대로 재사용할 수 있습니다.

## 테스트
`SessionVideoUrlIntegrationTests` (8건): READY 세션의 URL·만료·contentType, 준비 전/조립 중 409, 만료·삭제 410, 타인·없는·삭제 세션 404, 경로 형식 오류, Bearer 필수.
