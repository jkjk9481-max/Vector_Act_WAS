# C05 Chunk 업로드 검증 완료

`POST /api/coaching-sessions/{sessionId}/chunks/{chunkIndex}/complete` · 성공 200 · Bearer · 본문 없음

클라이언트가 청크 업로드를 마쳤다고 알리면, **저장소에 실제로 올라간 객체**의 크기·SHA-256을 C04에서 선언한 값과 대조해 일치할 때만 청크를 `VERIFIED`로 확정합니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/SessionChunkController` | `complete` 엔드포인트 추가 |
| `business/ChunkVerifyService` | 소유·상태 확인, 저장소 객체 대조, 상태 확정 |
| `business/VideoStorage` | `stat`(실제 크기·해시 조회), `delete` 추가 |
| `business/S3VideoStorage` / `InMemoryVideoStorage` | 구현. S3는 HEAD로 체크섬 조회, 없으면 내려받아 계산 |
| `persistence/SessionChunk.markVerified` | RESERVED → VERIFIED 전이 |
| `dto/ChunkVerifyResponse` | 응답 (`chunkIndex`, `status=VERIFIED`, `duplicate`) |
| `ErrorCode` | `CHUNK_NOT_UPLOADED`, `CHECKSUM_MISMATCH`(409) 추가 |
| `SignupSecurityConfig` | 이 엔드포인트를 CSRF 제외 (Bearer 전용) |

## 처리 순서
1. 청크 번호 범위(0~120) 아니면 `400 VALIDATION_ERROR`
2. 회원 행 잠금 후 세션 조회. 타인·삭제된·없는 세션은 `404`
3. 세션 상태: `RECORDING` 또는 마감 전 `FINALIZING`만 가능. 마감 후 `410 UPLOAD_EXPIRED`, 그 외 `409 SESSION_STATE_CONFLICT` (C04와 같은 규칙)
4. 예약(C04)된 적 없는 청크 번호는 `404`
5. 이미 `VERIFIED`면 `duplicate=true`로 같은 결과 반환(멱등)
6. 저장소에 객체가 없으면 `409 CHUNK_NOT_UPLOADED`
7. 크기 또는 SHA-256이 선언과 다르면 `409 CHECKSUM_MISMATCH`. 잘못된 객체는 삭제하고 예약은 유지해, C04로 URL을 다시 받아 재업로드할 수 있습니다
8. 일치하면 `VERIFIED` + `verified_at` 기록

## 명세에 없어서 정한 값 (확인 필요)
- **예약되지 않은 청크 번호**: `404 RESOURCE_NOT_FOUND`로 처리했습니다. `CHUNK_NOT_UPLOADED`로 볼 여지도 있습니다.
- **불일치 객체 삭제**: 명세에 규정이 없으나, 잘못된 파일이 남아 재업로드를 막는 일을 피하려고 삭제합니다.
- **잠금 범위**: 저장소 조회를 회원 행 잠금 안에서 실행합니다. 같은 회원의 청크 요청이 순차 처리됩니다.
- 실제 S3에서의 HEAD 체크섬 동작은 검증하지 못했습니다(테스트는 메모리 저장소 기준).

## 테스트
`ChunkVerifyIntegrationTests` (12건): 정상 검증과 상태 확정, 이미 검증된 청크(duplicate), 미업로드 409, 내용·크기 불일치 409와 객체 삭제, 미예약 404, 세션 상태 409, FINALIZING 마감 전후, 타인·없는 세션 404, 번호 범위 400, Bearer 필수.
