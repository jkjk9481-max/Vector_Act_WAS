# D06 월간 분석

`GET /api/dashboard/monthly` · 성공 200 · Bearer · 본문 없음

D05 주간 분석과 응답 필드가 완전히 같고, 집계 단위만 KST 월(1일~말일)입니다. 집계·비교 로직은 `DashboardPeriodService`를 그대로 공유합니다(기간 경계만 다름).

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/DashboardController.monthly` | Query 파싱(`month`), 지역 예외 처리 |
| `business/DashboardPeriodService.getMonthly` | 월 경계 계산, 집계·비교는 D05와 같은 코드(`loadPeriod`/`buildMetric`) |
| `persistence/DashboardSummary`(+Repository) | `dashboard_summaries`를 `period_type = 'MONTHLY'`로 조회(D05와 같은 테이블) |
| `presentation/dto/DashboardPeriodResponse` | D05와 공유하는 응답 DTO |
| `docs/sql/D05_dashboard_summaries.sql` | 이미 D05에서 만든 테이블을 그대로 씁니다(추가 DDL 없음) |

## 동작
- Query: `month`(KST 월, `yyyy-MM`, 생략 시 KST 기준 이번 달). `YearMonth.parse`로 직접 검증하며(경로 변수가 아니라 컨트롤러에서 문자열로 받아 파싱), 형식이 아니거나 월이 1~12 밖이면 400.
- `periodStart`/`periodEnd`: 해당 월의 1일~말일(28/29/30/31일, 달마다 다름).
- 이전 기간은 바로 앞 달의 1일~말일입니다(길이가 다를 수 있습니다. 예: 3월의 이전 달은 2월).
- `practiceCount`/`analyzedSessionCount`/`metrics[]`/`comparisonStatus`/`summary`/`summaryStatus`/`statisticsUpdatedAt`의 계산 규칙은 D05와 동일합니다(자세한 내용은 `docs/D05-weekly-analysis.md` 참고).

## 명세에 없거나 정한 값 (확인 필요)
- D05와 같은 가정을 그대로 따릅니다: 기간 피드백 생성 트리거 미구현(캐시 행이 없으면 기본값), `comparisonStatus` 판단 순서, `previousAverage`/`delta`가 `AVAILABLE`일 때만 채워지는 것, 404 RESOURCE_NOT_FOUND 미구현.
- **이전 기간 정의**: "KST 월 단위"만 명시되어 있어 이전 기간은 바로 앞 달(1일~말일)로 정했습니다.

## 테스트
`DashboardMonthlyIntegrationTests`: 월 경계(31일/28일), 월 내 `practiceCount`/`analyzedSessionCount`, 이전 달과의 비교(`AVAILABLE`), `month` 생략 시 이번 달, `month` 형식·범위 오류 400, 캐시된 `dashboard_summaries` 행 반영, Bearer 필수. 집계 로직 자체(제외 조건, 7개 항목, `comparisonStatus` 분기 등)는 D05 테스트에서 이미 검증했으므로 중복 작성하지 않았습니다.
