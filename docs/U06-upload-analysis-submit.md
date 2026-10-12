# U06 업로드 영상 분석 접수

`POST /api/upload-analyses` · 성공 202 · Bearer + Idempotency-Key

READY 상태이고 본인 소유이며 아직 분석에 쓰이지 않은 업로드를 분석에 접수합니다.
`analyses` 테이블은 C10/C11이 쓰는 것과 같은 테이블이며(`source=UPLOADED_VIDEO`, `upload_id` 사용), 성장 통계(장기 분석)에서는 제외됩니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/UploadAnalysisController` | U06~U09 엔드포인트, 지역 예외 처리 |
| `business/UploadAnalysisService` | U06 접수, U07 상태 조회, U09 취소 |
| `business/AnalysisResultService#getForUpload` | U08 결과 조회(C10과 같은 매핑 로직 재사용) |
| `coaching/persistence/Analysis` | `uploadId`/`expiresAt` 컬럼 추가, `queuedForUpload`/`cancel` 팩토리·메서드 추가 |
| `dto/UploadAnalysisCreateRequest`, `UploadAnalysisResponse` | 요청·응답(U06/U07/U09 공용 응답) |
| `SignupSecurityConfig` | U06/U09 엔드포인트를 CSRF 제외 |

## 처리 순서
1. scriptContent(1~20000자)·situation(1~2000자) 검증 → `400 VALIDATION_ERROR`
2. 회원 잠금 → 멱등 기록 확인(같은 키·같은 본문 재시도는 처음 응답 복원, 다른 본문은 `409 IDEMPOTENCY_CONFLICT`)
3. 업로드 소유·존재(404) → READY가 아니면 `409 UPLOAD_NOT_READY`
4. 이미 이 업로드를 가리키는 분석이 있으면 `409 UPLOAD_IN_USE`
5. `analyses` 행을 QUEUED(시도 1)로 접수

## 명세에 없거나 정한 값 (확인 필요)
- 이 클래스도 "접수"까지만 합니다. AI 서버에 실제 분석을 요청하는 단계는 C11/AnalysisRetryService와 같은 이유로 구현하지 않았습니다.
- 멱등 처리 순서는 C01과 동일하게 "업로드 상태 검사보다 멱등 기록 복원을 먼저" 합니다.

## 테스트
`UploadAnalysisSubmitIntegrationTests`: 정상 접수와 DB 상태, 같은 키 재시도 복원, 다른 본문 충돌, READY가 아닌 업로드 거부(409 UPLOAD_NOT_READY), 이미 분석된 업로드 거부(409 UPLOAD_IN_USE), 타인·없는 업로드 404, 입력 검증(400), Bearer 필수.
