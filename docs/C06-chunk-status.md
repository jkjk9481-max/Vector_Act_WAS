# C06 Chunk 현황 조회

`GET /api/coaching-sessions/{sessionId}/chunks` · 성공 200 · Bearer · 본문 없음

세션의 청크 목록과 누락 번호를 돌려주는 읽기 전용 API입니다. 클라이언트가 재전송할 청크를 판단하는 데 씁니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/SessionChunkController` | `status` 엔드포인트 추가 |
| `business/ChunkStatusService` | 소유 확인, 청크 조회, 누락 번호 계산 (읽기 전용) |
| `persistence/SessionChunkRepository` | 세션의 청크를 번호 순으로 조회 |
| `dto/ChunkStatusResponse` | `items[]`, `missingChunkIndexes` |

## 동작
- `items`: 세션의 청크를 `chunkIndex` 오름차순으로 반환합니다(최대 121개, 페이지 없음). 각 항목은 `chunkIndex`, `status`(RESERVED/VERIFIED), `startMs`, `endMs`, `sizeBytes`, `sha256`.
- `missingChunkIndexes`: `0`부터 현재 선언된 가장 큰 청크 번호까지 범위에서 **VERIFIED가 아닌** 번호(미선언 + RESERVED). 청크가 없으면 빈 배열.
- 세션 상태와 무관하게 소유자는 조회할 수 있습니다.
- 타인 소유·삭제된·없는 세션은 `404 RESOURCE_NOT_FOUND`, 경로의 `sessionId`가 UUID가 아니면 `400`.

## 명세에 없어서 정한 값 (확인 필요)
- **"선언 범위" 기준**: C07(촬영 종료)의 `lastChunkIndex`가 선언 범위일 가능성이 높지만 C07 전이라, 현재는 선언된 청크 중 가장 큰 번호를 범위 끝으로 삼았습니다. 또한 DB 설계서에는 `lastChunkIndex`를 저장할 컬럼이 없어, C07 구현 시 저장 위치 결정이 필요합니다(문서 보강 필요).
- **누락의 정의**: 선언은 됐지만 업로드가 끝나지 않은 `RESERVED`도 누락에 포함했습니다. 미선언 번호만 누락으로 볼 수도 있습니다.

## 테스트
`ChunkStatusIntegrationTests` (8건): 빈 세션, 번호 순 정렬과 누락 계산(미선언·RESERVED), 전부 검증 시 누락 없음, 세션 상태 무관 조회, 다른 세션 청크 미포함, 타인·없는·삭제 세션 404, 경로 형식 오류, Bearer 필수.
