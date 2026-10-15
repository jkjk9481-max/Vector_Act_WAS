# D01 연습 기록 목록

`GET /api/coaching-sessions` · 성공 200 · Bearer · 본문 없음

본인의 실시간 코칭(연습) 세션 목록을 페이지로 조회하는 읽기 전용 API입니다. 업로드 영상 분석은 별도 도메인(테이블)이라 구조적으로 포함되지 않고, 취소(CANCELED)·삭제된 세션은 명세의 지시(`취소·삭제·업로드 분석 제외`)에 따라 쿼리에서 제외합니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/CoachingSessionController.list` | Query 파싱, Bearer는 기존 보안 체인이 처리(새 공개/CSRF 예외 불필요: GET은 CSRF 대상이 아니고 publicRequests에도 없음) |
| `business/CoachingSessionListService` | 페이지·정렬·기간 검증, 목록 조회, overallScore 조합 |
| `persistence/CoachingSessionRepository.findForList` | 소유자·미삭제·미취소 조건의 페이지 조회(JPQL) |
| `persistence/AnalysisRepository.findBySessionIdIn` | 목록 페이지의 세션 ID들로 분석을 한 번에 조회(N+1 방지) |
| `presentation/dto/CoachingSessionListResponse` | 응답 DTO(`items[]` + 페이지 메타) |

## 동작
- Query: `page`(기본 0), `size`(기본 20, 최대 100), `sort`(`createdAt,desc`|`createdAt,asc`, 기본 desc), `from`/`to`(KST 날짜, `yyyy-MM-dd`).
- `items[]` 필드: `sessionId`, `createdAt`, `durationMs`, `status`, `videoStatus`, `analysisStatus`, `overallScore`(소수 1자리, C10과 같은 반올림 규칙). 분석이 없거나 완료 전이면 `overallScore`는 null.
- 응답 메타: `page`, `size`, `totalElements`, `totalPages`(`totalElements`가 0이면 0 — Spring Data의 기본 `Page.getTotalPages()`는 0건이어도 1을 주므로 직접 계산), `hasNext`.
- `from`/`to` 중 한쪽만 있으면 30일 범위로 보완(`from`만 있으면 `from~from+29일`, `to`만 있으면 `to-29일~to`). 둘 다 있으면 최대 366일 범위까지 허용. `to`가 `from`보다 이르거나 범위를 넘으면 400.
- 날짜는 KST 자정 기준으로 변환해 `createdAt`(UTC 저장) 범위 조건에 사용합니다(경계: `from` 00:00 KST 포함 ~ `to` 다음날 00:00 KST 미포함).
- `page`<0, `size`가 1~100 밖, `sort`가 허용값 밖, `from`/`to` 형식 오류는 모두 400 `VALIDATION_ERROR`.

## 구현 시 참고(명세서와의 차이)
- API 명세서 D01에 `오류: 404 RESOURCE_NOT_FOUND`가 적혀 있지만, 이 API는 경로 자원이 없는 목록 조회라 "없음"이 곧 빈 배열입니다(C09 등 단일 자원 조회의 404와는 다른 경우). 같은 404는 명세서 맨 앞의 공통 오류 목록에도 이미 있어, D01 섹션의 표기는 그 반복으로 보고 별도 404 분기를 구현하지 않았습니다. 명세와 다르게 판단한 부분이니 확인 부탁드립니다.

## 테스트
`CoachingSessionListIntegrationTests` (17건): 빈 목록, 필드·점수 매핑, 점수 없는 세션, 취소·삭제·타인 세션 제외, 기본/역순 정렬, 페이징과 `hasNext`, KST 기준 기간 필터, 한쪽만 준 날짜의 30일 보완, `page`/`size`/`sort`/날짜 형식/범위 오류, Bearer 필수.
