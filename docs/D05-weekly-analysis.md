# D05 주간 분석

`GET /api/dashboard/weekly` · 성공 200 · Bearer · 본문 없음

본인의 실시간 코칭(연습) 세션을 KST 기준 월요일~일요일 단위로 집계하는 읽기 전용 API입니다. 업로드 영상 분석은 대상이 아니며, 취소(CANCELED)·삭제된 세션은 제외합니다(D01·DB 설계서 7장과 같은 기준).

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/DashboardController.weekly` | Query 파싱, 지역 예외 처리 |
| `business/DashboardPeriodService.getWeekly` | 기간 계산(KST 월요일 시작), 집계, 이전 기간 비교. D06과 같은 집계 로직을 공유합니다(`getMonthly`) |
| `persistence/CoachingSessionRepository.findForDashboard` | 소유자·미삭제·미취소 조건의 기간 내 세션 조회 |
| `persistence/AnalysisRepository.findBySessionIdIn`, `AnalysisScoreRepository.findByAnalysisIdIn` | 세션들의 분석·항목별 점수를 한 번에 조회(N+1 방지) |
| `persistence/DashboardSummary`(+Repository) | `dashboard_summaries` 엔티티(DB 설계서 3.15). 이 API는 읽기만 합니다 |
| `presentation/dto/DashboardPeriodResponse` | 응답(`metrics[]` 7개 항목 등). D05·D06이 필드가 같아 공유합니다 |
| `docs/sql/D05_dashboard_summaries.sql` | 운영 DB 적용용 DDL |

## 동작
- Query: `date`(KST 날짜, `yyyy-MM-dd`, 생략 시 KST 기준 오늘). 그 날짜가 속한 KST 월요일을 `periodStart`, 그 다음 일요일을 `periodEnd`로 씁니다.
- `practiceCount`: 기간 내 비취소·비삭제 세션 중 영상이 한 번이라도 확정됐던 세션 수(`videoStatus`가 `READY`/`EXPIRED`/`DELETED`인 경우 — D03에서 "확정 후 만료·삭제"로 쓰는 것과 같은 구분입니다. `NOT_STARTED`/`UPLOADING`/`ASSEMBLING`/`FAILED`는 확정된 적이 없으므로 제외).
- `analyzedSessionCount`: 기간 내 세션 중 분석 상태가 `COMPLETED` 또는 `PARTIAL`인 세션 수.
- `metrics[]`는 `expression`/`voice`/`gaze`/`posture`/`emotion`/`scriptDelivery`/`situationFit` 7개가 항상 이 순서로 존재합니다(C10과 같은 목록). 각 항목의 `average`/`sampleCount`는 해당 기간에 `analysis_scores.status = AVAILABLE`인 점수만으로 계산합니다(DB 설계서 7장). 점수는 소수 1자리로 반올림합니다.
- 이전 기간(바로 앞 7일, 같은 길이)과 비교해 `previousAverage`/`delta`/`comparisonStatus`를 채웁니다. `comparisonStatus`는 다음 순서로 판단합니다.
  1. 이번 기간 표본이 3개 미만(0 포함)이거나 이전 기간 표본이 3개 미만이면 `INSUFFICIENT_DATA`(명세 "비교 표본이 각 기간 3개 미만이면 delta=null").
  2. (1에 해당하지 않을 때) 이전 기간에 표본이 전혀 없으면 `NO_PREVIOUS_DATA`.
  3. 두 기간에서 쓰인 분석의 `scoring_version` 집합이 다르면 `VERSION_MISMATCH`(DB 설계서 7장).
  4. 그 외에는 `AVAILABLE`로 `previousAverage`/`delta`를 채웁니다.
  - `AVAILABLE`이 아니면 `previousAverage`/`delta`는 모두 null입니다("비교 가능 이전 기간 평균"이라는 필드 설명을 그렇게 해석했습니다).
  - 1·2의 순서는 `docs/VectorAct_messages_json_확정.zip`의 `05_SUMMARY_REQUEST.json` 예시로 확인했습니다: `posture`는 이번·이전 기간 모두 표본이 0개인데 `comparisonStatus`가 `INSUFFICIENT_DATA`로 와 있어(표본이 있는 `situationFit`이 이전 기간만 없을 때 `NO_PREVIOUS_DATA`인 것과 대조), "이번 기간 표본 부족"이 "이전 기간 전무"보다 우선한다는 뜻으로 보고 순서를 맞췄습니다(처음 구현 때는 이전 기간 전무를 먼저 봐서 반대로 구현했던 것을 이 예시로 바로잡았습니다).
- `summary`/`summaryStatus`/`statisticsUpdatedAt`은 `dashboard_summaries`에서 `(user_id, WEEKLY, periodStart)`로 조회합니다. 캐시 행이 없으면 유효 분석 세션이 0건이면 `INSUFFICIENT_DATA`, 1건 이상이면 `PENDING`을 돌려주고 `statisticsUpdatedAt`은 조회 시각입니다.

## 명세에 없거나 정한 값 (확인 필요)
- **기간 피드백 생성 트리거**: DB 설계서는 "LIVE_CAPTURE 분석 확정 또는 기록 삭제 시 해당 주간·월간 행을 PENDING으로 바꾸고 AI 서버에 생성을 요청"한다고 하지만, 분석 결과 생성·AI 서버 연동 자체가 이 코드베이스에 아직 없습니다(C10 문서에도 같은 설명). 이 API는 `dashboard_summaries`를 읽기만 하며, 캐시 행을 만들거나 AI 서버에 요청을 보내지 않습니다. 캐시 행이 없을 때의 기본값(위 동작 설명)은 그 부재를 메우기 위해 정한 값입니다.
- **`comparisonStatus` 판단 순서와 `VERSION_MISMATCH` 기준**: 명세에는 "scoring_version이 다른 기간은 VERSION_MISMATCH 대상"이라고만 있고 `NO_PREVIOUS_DATA`/`INSUFFICIENT_DATA`와의 우선순위가 명시되지 않아 위 순서(데이터 없음 → 표본 부족 → 버전 불일치)로 정했습니다. 버전 비교는 기간별로 유효 점수를 만든 분석들의 `scoring_version` 집합(중복 제거)을 비교합니다.
- **`previousAverage`/`delta`가 null이 되는 범위**: `comparisonStatus`가 `AVAILABLE`이 아니면 항상 null로 처리했습니다(실제 이전 기간 평균이 존재해도 "비교 가능"하지 않으면 보여주지 않는다는 해석).
- **404 RESOURCE_NOT_FOUND**: API 명세서 D05 절에 적혀 있지만, 이 API에는 경로 자원이 없어(D01과 같은 이유) 별도 404 분기를 구현하지 않았습니다. 명세서 맨 앞 공통 오류 목록의 반복으로 보았습니다.
- **이전 기간 정의**: "KST 월요일 시작"만 명시되어 있어 이전 기간은 바로 앞 7일(같은 길이)로 정했습니다.

## 테스트
`DashboardWeeklyIntegrationTests`: 빈 주(표본 0개 → `INSUFFICIENT_DATA`), 이번 기간 표본은 충분하고 이전 기간만 없는 경우(`NO_PREVIOUS_DATA`), `practiceCount`/`analyzedSessionCount` 계산, 취소·삭제·타인·업로드 세션 제외, 영상 상태별 `practiceCount` 포함·제외, 7개 항목 고정 노출과 점수 평균·반올림, 이전 기간과의 `delta`/`comparisonStatus`(AVAILABLE/NO_PREVIOUS_DATA/INSUFFICIENT_DATA/VERSION_MISMATCH), `date` 생략 시 오늘이 속한 주, KST 월요일 경계, `date` 형식 오류 400, 캐시된 `dashboard_summaries` 행 반영, 캐시 없을 때 기본값, Bearer 필수.
