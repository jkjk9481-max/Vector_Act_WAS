# S01·S02 이미지 OCR 접수와 결과 조회

| API | 설명 | 인증 |
| --- | --- | --- |
| `POST /api/script-extractions` | multipart `file`(JPEG/PNG 1B~10MiB)로 OCR 작업 접수, 202 | Bearer (CSRF 제외) |
| `GET /api/script-extractions/{extractionId}` | 작업 상태·결과 조회, 200 | Bearer |

응답 필드는 두 API가 같습니다. 필드는 항상 포함되며 값이 없으면 `null`입니다.

```json
{"success":true,"data":{"jobId":"...","status":"QUEUED","content":null,"failureCode":null,"expiresAt":"2026-10-11T08:00:00Z"},"message":"OK","error":null}
```

`status`는 `QUEUED → PROCESSING → COMPLETED | FAILED`이며, 완료 시 `content`(1~20000자), 실패 시 `failureCode`가 채워집니다.
`expiresAt`은 접수 24시간 뒤이며 이 시각 이후 작업과 결과는 삭제됩니다.

## 오류

| 상태 | 코드 | 상황 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | `file` 누락, 빈 파일, 25메가픽셀 초과 이미지, `extractionId` 형식 오류 |
| 413 | FILE_TOO_LARGE | 10MiB 초과 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 실제 내용이 JPEG/PNG가 아님(헤더·확장자는 신뢰하지 않음), multipart가 아닌 요청 |
| 429 | RATE_LIMITED | 회원별 기본 60초 10회 초과(S01만 적용) |
| 410 | RESULT_EXPIRED | 만료된 작업(S02) |
| 404 | RESOURCE_NOT_FOUND | 없는 작업, 다른 회원의 작업, 탈퇴 회원 |

## 구조

```text
ScriptExtractionController   크기 검사, 회원별 요청 제한, 202/200 응답
  → ScriptJobService         (S01) 이미지 검증 → 저장소 저장 → script_jobs 저장 → 커밋
  → 커밋 후 ScriptJobEventListener → scriptOcrExecutor
      → ScriptOcrProcessor    start → OcrEngine.recognize → complete/fail → 원본 이미지 삭제
  → ScriptJobPurgeScheduler    만료 작업과 남은 이미지 정리(기본 1시간 주기)
```

- 엔진 호출은 DB 트랜잭션 밖에서 실행하고, 상태 전이는 각각 별도 트랜잭션(`REQUIRES_NEW`)으로 확정합니다.
- 저장소 객체는 DB 롤백으로 되돌릴 수 없어, 작업 저장 실패 시 방금 저장한 이미지를 롤백 후 삭제합니다.
- 인터페이스로 분리한 것: `OcrEngine`(엔진), `ScriptImageStorage`(원본 이미지 임시 저장소).

## 임시 구현과 한계 (운영 전에 교체 필요)

- **`OcrEngine`은 `UnavailableOcrEngine`**이 기본입니다. 실제 엔진이 연결될 때까지 모든 작업이 `FAILED`(`OCR_ENGINE_UNAVAILABLE`)로 끝납니다.
  OCR 실행 주체(WAS 내장 / AI 서버 / 외부)는 문서에 확정되어 있지 않습니다. AI 서버 설계서는
  "S01·S02는 애플리케이션 서버에서 제공"이라고만 하며 AI 서버 인터페이스 목록에 OCR 엔드포인트가 없습니다.
  `OcrEngine` Bean을 등록하면 기본 구현은 사용되지 않습니다.
- **`ScriptImageStorage`는 메모리 구현**입니다. 재시작하면 이미지가 사라지고 여러 서버 간에 공유되지 않습니다. S3 구현체로 교체해야 합니다.
- 처리 대기열은 메모리 스레드 풀(2개, 대기 100개, 가득 차면 호출 스레드가 처리)입니다. **서버 재시작 시 `QUEUED`/`PROCESSING`
  작업은 복구되지 않고** 그 상태로 남아 있다가 만료 때 정리됩니다.
- 이미지 삭제가 실패하면 key가 남고 만료 정리가 다시 시도합니다. 회원 Hard Delete 시 24시간 안에 남은 이미지는
  FK CASCADE로 행이 먼저 사라져 객체 정리가 되지 않을 수 있습니다.

## 명세에 없어 정한 값 (확인 필요)

| 항목 | 정한 값 |
| --- | --- |
| failureCode 값 | `OCR_ENGINE_UNAVAILABLE`, `OCR_NO_TEXT`(빈 결과), `OCR_RESULT_TOO_LONG`(20000자 초과, 자르지 않음), `OCR_FAILED`(그 외) |
| 존재하지 않거나 남의 작업 조회 | 404 `RESOURCE_NOT_FOUND`(명세는 S02 오류로 410만 기재). 만료 후 정리까지는 410, 삭제된 뒤에는 404 |
| 요청 제한 | S01 회원별 10회/60초(`SCRIPT_OCR_RATE_LIMIT_*`) |
| 원본 이미지 보관 | 명세는 접수 24시간 후 삭제. 결과 확정 직후 즉시 삭제(데이터 최소화) |
| 이미지 크기 상한 | 25메가픽셀 초과는 400 |

## 설정과 DB

- `SCRIPT_OCR_ASYNC`(true), `SCRIPT_OCR_RATE_LIMIT_MAX_ATTEMPTS`(10), `SCRIPT_OCR_RATE_LIMIT_WINDOW_SECONDS`(60),
  `SCRIPT_OCR_PURGE_ENABLED`(true), `SCRIPT_OCR_PURGE_DELAY_MS`(3600000), `SCRIPT_OCR_PURGE_INITIAL_DELAY_MS`(120000)
- 운영 DB에는 배포 전에 `docs/sql/S01_script_jobs.sql`을 적용합니다(자동 실행 없음, 이번 작업에서 실행하지 않음).
- `request_data`(JSONB) 컬럼은 현재 사용하지 않아 엔티티에 매핑하지 않았습니다.

## 테스트

`ScriptExtractionIntegrationTests`: 접수와 완료까지의 상태 전이, 원본 바이트 전달, 실패 코드 4종과 메시지 비노출,
20000자 경계, 입력 오류(빈 파일/413/415/JSON/part 누락), Bearer 필수·CSRF 불필요, 타 회원·미존재·잘못된 ID,
만료 410과 정리, 저장소 실패, 작업 저장 실패 시 롤백과 이미지 삭제, 회원별 요청 제한, 탈퇴 회원.
