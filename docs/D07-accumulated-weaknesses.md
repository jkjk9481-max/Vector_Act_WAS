# D07 누적 취약점

`GET /api/dashboard/weaknesses` · 성공 200 · Bearer · 본문 없음 · Query 없음

본인의 실시간 코칭 세션 전체(기간 제한 없음)를 대상으로 `analysis_feedbacks.kind = IMPROVEMENT`를 category·subcategory별로 세션 단위 집계하는 읽기 전용 API입니다(DB 설계서 7장). "개선 정도"만 KST 기준 최근 30일과 그 직전 30일을 비교합니다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `presentation/DashboardController.weaknesses` | 엔드포인트(Query 없음) |
| `business/DashboardWeaknessService` | 전체·최근 30일·직전 30일 집계, `frequency`/`improvement`/`improvementStatus` 계산, `suggestion` 선정 |
| `persistence/CoachingSessionRepository.findAllForDashboard` | 기간 제한 없는(all-time) 소유자·미삭제·미취소 세션 조회 |
| `persistence/AnalysisRepository.findBySessionIdIn`, `AnalysisFeedbackRepository.findByAnalysisIdIn` | 세션들의 분석·피드백을 한 번에 조회(N+1 방지) |
| `presentation/dto/DashboardWeaknessResponse` | 응답(`items[]`, `items[].subItems[]`) |

## 동작
- 대상: 본인 소유, 삭제되지 않음, 취소(CANCELED) 아님, 분석 상태가 `COMPLETED`/`PARTIAL`인 세션("분석 가능 세션" = `eligible`). 분석이 전혀 없으면 `items: []`를 돌려줍니다("빈 배열 가능").
- 분석 가능 세션이 1건 이상이면 `items[]`는 항상 7개(`expression`/`voice`/`gaze`/`posture`/`emotion`/`scriptDelivery`/`situationFit`, D05와 같은 순서)입니다.
- `eligibleSessionCount`: 전체 기간의 분석 가능 세션 수(카테고리와 무관하게 동일한 값 — "분석 가능"은 세션 단위 속성이라서).
- `affectedSessionCount`: 그 중 해당 category(또는 subcategory)에 `IMPROVEMENT` 피드백이 1개 이상 있던 세션 수(같은 세션에 같은 항목 피드백이 여러 개여도 세션 단위로 1번만 셉니다).
- `frequency = affected/eligible`(소수 2자리), `eligible`이 0이면 null.
- `improvement`: KST 기준 조회일을 포함한 최근 30일과 그 직전 30일의 `frequency`를 따로 계산해 `직전 - 최근`으로 산출합니다(양수면 개선). 세션은 `createdAt`(KST 날짜 기준)으로 두 창에 배정합니다. `improvementStatus`는 D05·D06(`DashboardPeriodService`)과 같은 우선순위로 판단합니다.
  - 최근 30일 쪽 분석 가능 세션이 3개 미만(0 포함)이거나 직전 30일 쪽이 3개 미만이면 `INSUFFICIENT_DATA`.
  - (위에 해당하지 않을 때) 직전 30일에 분석 가능 세션이 0건이면 `NO_PREVIOUS_DATA`.
  - 그 외에는 `AVAILABLE`.
  - `AVAILABLE`이 아니면 `improvement`는 null입니다.
- `suggestion`: 해당 category의 `IMPROVEMENT` 피드백 중 `suggestion`이 있는 가장 최근(`created_at` 기준) 문구. 하나도 없으면 팀이 작성한 기본 문구를 씁니다(`docs/VectorAct_코칭문구_최종.xlsx` "결과·대시보드 문구" 시트, 용도 = 누적 취약점 개선 방향, 버전 1 — AI 서버 전용 DB `vectoract_ai.coaching_phrases`의 `usage=WEAKNESS_DIRECTION`에 적재될 문구의 복사본). `voice`/`emotion`처럼 세부 항목이 여러 개인 category는 첫 번째 세부 항목(`voice`→`speechRate`, `emotion`→`emotionExpression`)의 문구를 대표로 씁니다. `posture`는 세부 항목이 없어(코칭문구 시트에도 해당 문구 없음) 일반 문구를 둡니다.
- `subItems[]`: category별 세부 항목 코드로 같은 집계를 반복합니다. `eligibleSessionCount`는 상위 `items[]`와 같은 값(세션의 분석 가능 여부는 세부 항목과 무관)입니다. `posture`는 DB CHECK상 피드백이 생길 수 없어(`docs/sql/C10_analysis_tables.sql`) 세부 항목이 없고 `subItems: []`입니다.
  - 세부 항목 코드: `expression`→`facialExpression`, `gaze`→`gazeHold`, `voice`→`speechRate`/`volume`/`intonation`, `emotion`→`emotionExpression`/`emotionTransition`, `scriptDelivery`→`scriptAccuracy`, `situationFit`→`situationFit`.
- `updatedAt`: 응답을 만든 시각(별도 집계 캐시 테이블 없이 매 요청마다 계산합니다 — DB 설계서 7장 "별도 statistics 테이블은 v1 필수로 두지 않으며").

## 명세에 없거나 정한 값 (확인 필요)
- **`items[]`가 항상 7개인 조건**: 명세는 "최대 7개, 빈 배열 가능"이라고만 해 "7개 미만이지만 빈 배열도 아닌" 중간 상태를 명시하지 않았습니다. 분석 가능 세션이 전혀 없을 때만 빈 배열로, 그 외에는 항상 7개(D05·C10과 같은 "7개 항목은 항상 존재" 패턴)로 정했습니다.
- **`eligibleSessionCount`가 category와 무관하게 같은 값인지**: 명세에 category별로 다를 수 있다는 근거가 없어, "분석 가능 세션"은 세션 단위 속성(그 세션의 분석이 COMPLETED/PARTIAL인지)으로만 보고 모든 category·subcategory에 같은 분모를 적용했습니다.
- **`suggestion` 선정 기준**: "AI 서버 제공, 응답 불가 시 최근 문구 또는 기본 문구"라고만 되어 있고 AI 서버 연동이 없어, 저장된 피드백 중 가장 최근 문구로 대체했습니다. 기본 문구는 팀이 작성한 `docs/VectorAct_코칭문구_최종.xlsx`의 "누적 취약점 개선 방향" 문구(버전 1)를 그대로 썼습니다. 이 표는 category가 아니라 **세부 항목(subcategory)** 단위로 문구가 있어서, `voice`/`emotion`처럼 세부 항목이 여럿인 category의 대표 문구는 첫 번째 세부 항목 것을 쓰기로 정했습니다(명세에 선정 기준이 없음). 같은 표의 버전 2(대체 문구)는 현재 쓰지 않습니다.
- **404 RESOURCE_NOT_FOUND**: D05·D06과 같은 이유로(경로 자원 없음) 구현하지 않았습니다.
- **`improvementStatus` 판단 순서**: 명세에 `NO_PREVIOUS_DATA`/`INSUFFICIENT_DATA` 우선순위가 없어, D05에서 `docs/VectorAct_messages_json_확정.zip`(`05_SUMMARY_REQUEST.json`)의 확정 예시로 바로잡은 것과 같은 순서(이번 기간 표본 부족이 우선)를 그대로 적용했습니다. D07을 직접 보여주는 예시는 없어 D05·D06과의 일관성으로 정한 값입니다.
- **`frequency`/`improvement`의 소수 자릿수**: 명세에 반올림 규칙이 없어 소수 2자리(HALF_UP)로 정했습니다(0~1 비율이라 D05의 점수 소수 1자리와 다른 자릿수를 썼습니다).

## 테스트
`DashboardWeaknessIntegrationTests`: 분석 가능 세션 없을 때 빈 배열, 7개 항목 고정 순서와 기본 문구, `voice`/`emotion` 대표 기본 문구(첫 번째 세부 항목) 선택, 세션 단위 중복 집계 방지, `voice` 세부 항목 3개 집계, 최신 피드백 문구 선택, 취소·삭제·타인 세션 제외, `improvementStatus`(NO_PREVIOUS_DATA/INSUFFICIENT_DATA/AVAILABLE, 두 창 모두 비었을 때의 `INSUFFICIENT_DATA` 포함)와 `improvement` 값, Bearer 필수.
