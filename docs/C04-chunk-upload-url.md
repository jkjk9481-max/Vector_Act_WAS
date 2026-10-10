# C04 Chunk 업로드 URL 발급

`POST /api/coaching-sessions/{sessionId}/chunks/{chunkIndex}/upload-url` · 성공 200 · Bearer

청크 하나를 올릴 자리를 예약하고 5분 유효한 임시 업로드 URL(Presigned PUT)을 발급합니다.
업로드 흐름: **C04(URL 발급)** → 클라이언트가 URL로 파일 PUT → C05(크기·해시 검증).

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/SessionChunkController` | 엔드포인트, 지역 예외 처리 |
| `business/ChunkUploadService` | 입력 검증, 소유·상태 확인, 청크 예약, URL 발급 |
| `business/VideoStorage` / `InMemoryVideoStorage` / `S3VideoStorage` | 업로드 URL 발급 저장소 계약과 구현(버킷 설정 시 S3) |
| `persistence/SessionChunk(Repository)` | `session_chunks` 엔티티 (DB 설계서 3.7) |
| `dto/ChunkUploadUrlRequest`, `ChunkUploadUrlResponse` | 요청·응답 |
| `ErrorCode` | `CHUNK_CONFLICT`(409), `UPLOAD_EXPIRED`(410) 추가 |
| `SignupSecurityConfig` | 이 엔드포인트를 CSRF 제외 (Bearer 전용) |
| `docs/sql/C04_session_chunks.sql` | 운영 DB 적용용 DDL (C01 SQL 이후 적용) |

## 처리 순서
1. 입력 검증 → `400 VALIDATION_ERROR` (chunkIndex 0~120, startMs ≥ 0, endMs > startMs 이고 ≤ 600000, sizeBytes ≥ 1, sha256 소문자 hex 64자)
2. `sizeBytes` > 32MiB → `413 FILE_TOO_LARGE` (int 범위를 넘는 값도 413)
3. 회원 행 잠금 후 세션 조회. 타인·삭제된·없는 세션은 `404`
4. 세션 상태 확인
   - `RECORDING`: 가능
   - `FINALIZING`: `upload_deadline_at`까지만 가능, 지나면 `410 UPLOAD_EXPIRED`
   - 그 외: `409 SESSION_STATE_CONFLICT`
5. 같은 번호의 기존 청크 확인
   - 없음: 새로 예약(RESERVED)하고 staging key `session-chunks/{sessionId}/{index}-{uuid}` 부여
   - 같은 내용(구간·크기·해시): 기존 key로 새 URL만 재발급(재시도 허용)
   - 다른 내용: `409 CHUNK_CONFLICT`
6. 업로드 URL 발급. `Content-Type`은 C02에서 기록한 입력 MIME, 서명에 크기와 SHA-256 체크섬 포함

## 명세에 없거나 정한 값 (확인 필요)
- **`FINALIZING` 허용**: 설계서 `upload_deadline_at`("finish 최초 접수 후 누락 청크 업로드 마감")과 `UPLOAD_EXPIRED`를 근거로 종료 후 마감까지 누락 청크 업로드를 허용했습니다. C07 구현 시 마감 시각을 설정해야 합니다.
- **충돌 기준**: 설계서는 "다른 sha256/size"만 명시했으나, 구간(startMs/endMs)이 다른 경우도 충돌로 처리했습니다.
- **`requiredHeaders`**: S3 서명 헤더 중 `host`, `content-length`는 브라우저가 자동 설정하므로 제외했습니다. 체크섬 헤더(`x-amz-checksum-sha256`)는 hex가 아니라 Base64로 서명됩니다. 실제 S3 연동은 검증하지 못했습니다.
- **`expires_at`**: 미완료 청크 정리 기준을 업로드 URL 만료 시각(발급 + 5분)으로 두었습니다. 정리 스케줄러는 만들지 않았습니다.
- 요청 제한(429)은 C04 오류 목록에 없어 적용하지 않았습니다.

## 테스트
`ChunkUploadUrlIntegrationTests` (11건): 정상 예약과 DB 상태, 같은 내용 재발급, 다른 내용 충돌(해시·크기·구간), 413과 경계값, 입력 검증, 청크 번호 범위, 세션 상태 409, FINALIZING 마감 전후(410), 타인·없는·삭제 세션 404, 경로 형식 오류, Bearer 필수.
