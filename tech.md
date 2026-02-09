# Tech Spec: 계근지 OCR 텍스트 파싱 시스템 (Java 17+)

---

## 요약

계근지(계량증명서)의 OCR 결과 JSON을 입력받아, 업무에 필요한 필드(날짜, 차량번호, 중량 등)를 정확하게 추출·정규화하여 구조화된 JSON으로 출력하는 파싱 시스템. OCR 특유의 노이즈(오탈자, 라벨 누락, 포맷 불규칙)에 견고하게 대응하며, 새로운 계근지 양식이 추가되어도 별칭 사전만 수정하면 확장 가능한 구조.

---

## 1. 핵심 특징

- **값 패턴 우선 분류(Value-First Classification)**: 라벨보다 값의 패턴(kg 단위, 날짜, GPS 등)을 먼저 판별하여 라인의 유형을 결정. 라벨은 세부 필드 구분 힌트로만 사용.
- **엄격한 중량 확정**: kg 단위 필수. kg 없는 라인은 중량으로 분류하지 않음.
- **미확정 허용(UNRESOLVED)**: 공차/실중량 구분이 불확실하면 억지로 할당하지 않고 UNRESOLVED 상태로 출력. 산술은 맞지만 의미가 틀린 "조용한 오류"를 원천 방지.
- **추론 경로 기록(Provenance)**: 각 중량 필드에 `inferred_by`(label/arithmetic)를 기록하여 할당 근거를 추적 가능.
- **노이즈 마킹(삭제 아닌 마킹)**: 저신뢰 토큰을 삭제하지 않고 플래그만 부여하여 정보 손실 방지.
- **이중 검증 플래그**: `is_consistent`(내부 일관성)과 `is_actionable`(자동 처리 가능 여부)을 분리.
- **이중 confidence**: `ocr_confidence`(OCR 읽기 정확도)와 `assignment_confidence`(필드 할당 정확도)를 독립 관리.

---

## 2. 목표 및 비목표

### 목표

| # | 목표 | 측정 기준 |
|---|------|-----------|
| G1 | 4종 샘플 데이터에 대해 주요 필드 100% 정확 파싱 | 단위 테스트 통과 |
| G2 | OCR 노이즈(오탈자, 공백, 순서 변경)에 대한 견고한 파싱 | 변형 테스트셋 95%+ 정확도 |
| G3 | 무게 값의 정규화 (콤마 제거, 단위 통일, 정수 변환) | kg 정수 출력 일관성 |
| G4 | 날짜/시간의 ISO 8601 정규화 | `YYYY-MM-DD`, `HH:MM:SS` 형식 |
| G5 | 총중량 = 공차중량 + 실중량 교차 검증 | validation 플래그 출력 |
| G6 | JSON 구조화 출력 (원본 라벨/값 + 정규화 값 + 필드별 status) | 스키마 준수 검증 |

### 비목표

| # | 비목표 | 이유 |
|---|--------|------|
| N1 | OCR 엔진 자체 개발/교체 | 입력은 이미 OCR 결과 JSON으로 전제 |
| N2 | 이미지 전처리 파이프라인 | OCR 이전 단계는 스코프 밖 |
| N3 | 웹 UI / API 서버 구축 | CLI 도구로 충분 |
| N4 | ML 기반 NER 모델 학습 | 4종 샘플 규모에서는 규칙 기반이 효율적 |
| N5 | 실시간 스트리밍 처리 | 배치 처리로 충분 |

---

## 3. 샘플 데이터 분석

### 3.1 공통 추출 필드

| 필드 | sample_01 | sample_02 | sample_03 | sample_04 |
|------|-----------|-----------|-----------|-----------|
| 문서 제목 | 계량증명서 | 계그표(계근표) | 계량확인서 | 계량증명표 |
| 계량 일자 | 2026-02-02 | 2026-02-02 | 2026-02-01 | 2025-12-01 |
| 차량 번호 | 8713 | 80구8713 | 5405 | 0580 |
| 거래처/상호 | 곰욕환경폐기물 | 고요환경 | - | 신성(푸디스트) |
| 품명 | - | 식물 | - | 국판 |
| 총중량 (kg) | 12,480 | 13,460 | 14,080 | 14,230 |
| 공차중량 (kg) | 7,470 | 7,560 | 13,950 | 12,910 |
| 실중량 (kg) | 5,010 | 5,900 | 130 | 1,320 |
| 발행사 | 동우바이오(주) | 장원C&S | 정우리사이클링(주) | (주)하은펄프 |
| GPS 좌표 | 37.105317, 127.375673 | 37.718114, 126.844940 | - | - |
| 계량 시각 | 05:26:18 / 05:36:01 | 02:07 / 02:13 | 11:33 / 11:39 | 09:09 / 09:09 |
| 구분 | - | 입고 | 입고 | 입고 |

### 3.2 주요 노이즈 패턴

| 노이즈 유형 | 실제 예시 | 영향 |
|-------------|-----------|------|
| **라벨 오탈자** | `품종명랑` → 품명/품종명 (conf: 0.1857) | 라벨 매칭 실패 |
| **값 오탈자** | `곰욕환경폐기물` → 고요환경폐기물? (conf: 0.5133) | 값 왜곡 |
| **라벨 변형** | `계그표` → 계근표 (conf: 0.2495) | 문서 유형 인식 실패 |
| **띄어쓰기 분리** | `거 래 처:`, `실 중 량:`, `중 량:` | 라벨 토큰화 불일치 |
| **시간/중량 혼재** | `02:07 13 460 kg` (시간과 숫자 사이 공백) | 값 경계 모호 |
| **라벨 누락** | sample_01 `중량:` 행에 라벨 없이 시간+무게만 | 총중량/공차 구분 불가 |
| **무의미 토큰** | `없다.`, `N`, `공육을 unle` | 노이즈 필터링 필요 |
| **숫자 포맷** | `5 900` vs `5,010` vs `14,080` | 쉼표/공백 혼용 |

---

## 4. 기술 스택

| 레이어 | 기술 | 선정 이유 |
|--------|------|-----------|
| **언어** | Java 17+ | record, switch expression, 텍스트 블록 |
| **빌드** | Gradle (Groovy DSL) | 의존성 관리, 테스트 자동화 |
| **정규식** | `java.util.regex.Pattern` | 날짜/시간/중량 패턴 추출의 핵심 |
| **날짜 처리** | `java.time` (LocalDate, LocalTime) | 불변 객체, 파싱 유연 |
| **JSON** | Jackson 2.17.0 (`ObjectMapper`, `@JsonProperty`) | 입출력 직렬화/역직렬화 |
| **라벨 매칭** | `String.contains()` 기반 자체 구현 | 한국어 계근지 라벨은 짧고 명확, 외부 라이브러리 불필요 |
| **데이터 모델** | Java record | 불변성 보장, 간결한 코드 |
| **테스트** | JUnit 5 + AssertJ 3.25.3 | 단위/통합 테스트, 유연한 assertion |

**의도적 미채택:**

- Apache Commons Text (FuzzyScore): 한국어 계근지 라벨은 짧고 명확하여 `String.contains()` 기반 매칭으로 충분
- picocli: CLI 프레임워크 없이 단순 `main(String[] args)`로 충분
- CSV 출력 (Apache Commons CSV): JSON 출력만으로 요구사항 충족
- ML 기반 NER: 4종 샘플 규모에서는 규칙 기반이 효율적

---

## 5. 아키텍처 설계

### 5.1 전체 파이프라인

```
┌──────────┐   ┌──────────────┐   ┌───────────┐   ┌───────────────┐   ┌────────────┐   ┌───────────┐   ┌────────┐
│ OCR JSON │──▶│ Preprocessor │──▶│ Extractor │──▶│ FieldAssigner │──▶│ Normalizer │──▶│ Validator │──▶│  JSON  │
│  (Input) │   │   (전처리)    │   │ (라인분류) │   │  (필드할당)    │   │ (값정규화)  │   │ (교차검증) │   │ Output │
└──────────┘   └──────────────┘   └───────────┘   └───────────────┘   └────────────┘   └───────────┘   └────────┘
```

각 단계는 독립 클래스로, 이전 단계의 출력을 다음 단계의 입력으로 전달하는 파이프-필터 구조.

### 5.2 모듈 구조

```
weighbridge-parser/
├── build.gradle
├── src/main/java/com/weighbridge/parser/
│   ├── WeighbridgeParserApplication.java     # CLI 엔트리포인트 (58줄)
│   ├── model/
│   │   ├── OcrInput.java                     # OCR JSON 입력 모델 (118줄)
│   │   ├── BaseField.java                    # 일반 필드 record (47줄)
│   │   ├── WeightField.java                  # 중량 필드 record (49줄)
│   │   ├── ParsedDocument.java               # 파싱 결과 최상위 모델 (32줄)
│   │   ├── ProcessedLine.java                # 전처리 결과 record (20줄)
│   │   ├── FieldStatus.java                  # enum: OK, WARNING, ERROR, MISSING, UNRESOLVED (27줄)
│   │   ├── InferredBy.java                   # enum: LABEL, ARITHMETIC, HEURISTIC (23줄)
│   │   ├── Severity.java                     # enum: LOW, MEDIUM, HIGH (23줄)
│   │   ├── ValidationResult.java             # 검증 결과 record (32줄)
│   │   └── ResolutionHint.java               # UNRESOLVED 힌트 record (22줄)
│   ├── pipeline/
│   │   ├── Preprocessor.java                 # 텍스트 전처리 (292줄)
│   │   ├── Extractor.java                    # 라인 분류 (138줄)
│   │   ├── LineType.java                     # 라인 유형 enum (26줄)
│   │   ├── ClassifiedLine.java               # 분류된 라인 record (23줄)
│   │   ├── WeightCandidate.java              # 중량 후보 record (26줄)
│   │   ├── FieldAssigner.java                # 필드 추출 및 역할 할당 (718줄)
│   │   ├── Normalizer.java                   # 값 정규화 (223줄)
│   │   └── Validator.java                    # 교차 검증 (302줄)
│   ├── config/
│   │   ├── FieldAliases.java                 # 라벨 별칭 사전 (36줄)
│   │   ├── AliasMatchingUtils.java           # String.contains() 기반 매칭 (75줄)
│   │   └── Thresholds.java                   # 모든 임계값 (32줄)
│   └── output/
│       └── JsonWriter.java                   # JSON 출력 (52줄)
└── src/test/java/com/weighbridge/parser/
    ├── PreprocessorTest.java                 # (205줄)
    ├── ExtractorTest.java                    # (176줄)
    ├── FieldAssignerTest.java                # (506줄)
    ├── NormalizerTest.java                   # (274줄)
    ├── ValidatorTest.java                    # (354줄)
    ├── AliasMatchingUtilsTest.java           # (83줄)
    ├── IntegrationTest.java                  # (387줄)
    ├── OutputTest.java                       # (95줄)
    └── fixtures/
        ├── sample_01.json
        ├── sample_02.json
        ├── sample_03.json
        └── sample_04.json
```

---

## 6. 핵심 컴포넌트 상세

### 6.1 Preprocessor (280줄) — 텍스트 전처리

#### 역할

OCR이 출력한 날것의 텍스트를 파싱 가능한 형태로 정리한다. 저신뢰 토큰을 삭제하지 않고 마킹만 해서 정보 손실을 방지한다.

#### 입력 소스 선택: lines[] vs text

OCR JSON에는 같은 텍스트가 두 가지 형태로 들어있다:

- `text` 필드: 전체 텍스트를 `\n`으로 이어붙인 단순 문자열
- `lines[]` 필드: OCR 엔진이 바운딩박스 기반으로 같은 줄의 글자들을 묶은 구조체. `words[]` 배열에 각 단어별 confidence를 포함.

`lines[]`를 1차 소스로 사용한다. 단어별 신뢰도(confidence)를 알 수 있기 때문이다. `text` 필드만 보면 `품종명랑`이 정상적인 라벨인지 오인식인지 판단할 근거가 없지만, `lines[].words[]`를 보면 confidence가 0.18로 극히 낮아서 "이건 오인식이니 별도 처리하자"는 판단이 가능해진다. `text` 필드는 `lines[]`가 없는 경우의 fallback으로만 사용한다.

`pages[].lines[]` 형식도 자동 감지하여 지원한다.

#### 처리 4단계

**Step 1. 라인 단위 분리**

`lines[]` 배열을 순회하면서 각 라인의 텍스트와 단어별 confidence를 가져온다.

**Step 2. 공백 정규화**

한국어 계근지에서 라벨은 글자 사이에 공백이 들어가는 경우가 많다. 원본 영수증 자체가 그렇게 인쇄되어 있기 때문이다:

```
Before                          After
──────────────────────         ──────────────────────
"계 량 증 명 서"            →   "계량증명서"
"거 래 처: 곰욕환경폐기물"  →   "거래처: 곰욕환경폐기물"
"실 중 량: 5,010 kg"       →   "실중량: 5,010 kg"
```

숫자와 단위 사이 공백(`12,480 kg`)은 유지한다. 한글 자모 사이의 단일 공백만 정규화한다.

**Step 3. 노이즈 마킹**

목적별 confidence threshold 분리:

```
(1) 삭제 (극단적 쓰레기만): confidence < 0.1 이면서 단독 토큰
    → 예: "N"(0.099), 단독 특수문자, 의미 없는 영문 1~2글자
    → 라인 전체가 이것들로만 구성된 경우에만 제거

(2) 노이즈 마킹: 라인 내 최저 word confidence < 0.3
    → is_noise_candidate: true
    → 삭제하지 않고 플래그만 부여
```

**Step 4. 라인 병합**

OCR은 물리적 위치 기준으로 라인을 나누므로, 논리적으로 하나의 필드인데 두 줄로 분리되는 경우가 있다:

```
Line 7: "중량:"                ← 라벨만 있고 값 없음
Line 8: "05:36:01 7,470 kg"  ← 값만 있고 라벨 없음
→ 병합: "중량: 05:36:01 7,470 kg"
```

병합 조건 (두 가지 모두 만족해야 병합):
1. **라벨 라인**: 콜론으로 끝나고, 숫자 값이 없으며, WEIGHT_ALIASES와 약매칭(≥ 50점)
2. **값 라인**: 중량 값 패턴 (`[시간] + 숫자 + kg`)을 만족

병합 금지: 전화번호(`TEL`/`FAX`), GPS 좌표, 일련번호 패턴

---

### 6.2 Extractor (138줄) — 라인 분류

#### 역할

전처리된 각 라인을 값 패턴 기반으로 7개 유형 중 하나로 분류한다. Value-First 원칙에 따라 라벨보다 값의 패턴을 먼저 판별한다.

#### 분류 우선순위

콜론 유무에 따라 두 경로로 분기:

**콜론이 있는 경우** (시간 내 콜론은 제외, 전각 콜론 `：` 허용):
- 콜론 + kg → `WEIGHT_EVENT`
- 콜론 + kg 없음 → `LABEL_VALUE_LINE`

**콜론이 없는 경우** (우선순위 순):
1. `TIMESTAMP_LINE`: `YYYY-MM-DD HH:MM:SS` 패턴
2. `GPS_LINE`: 위도/경도 좌표 패턴
3. `WEIGHT_EVENT`: kg + 3~6자리 정수
4. `DATE_LINE`: 날짜 패턴 (YYYY.MM.DD 등)
5. `ISSUER_LINE`: (주)/C&S 패턴 + negative lexicon 통과
6. `ETC_LINE`: 위 어느 것에도 해당하지 않음

#### weight_event 확정 조건

- **확정**: kg + 3~6자리 정수
- kg 단위 없는 라인은 중량으로 분류하지 않음

#### issuer 과탐 방지

negative lexicon: TEL, FAX, 차량, 품명이 포함된 라인은 issuer로 분류하지 않음.

---

### 6.3 FieldAssigner (718줄) — 필드 추출 및 역할 할당

#### 역할

분류된 라인에서 실제 필드 값을 추출하고, 특히 중량 3개(총중량/공차/실중량)의 역할을 결정한다. 파이프라인에서 가장 핵심적인 모듈.

#### Step 2-1: 중량 후보 파싱

`WEIGHT_EVENT`로 분류된 각 라인에서:
- 시간 패턴 추출 (HH:MM:SS 또는 HH:MM)
- 중량 값 추출 (3~6자리 정수, 콤마/공백 제거)
- 단위 추출
- OCR confidence 집계

결과: `WeightCandidate(weight, time, unit, rawLabel, ocrConfidence, lineIndex)`

#### Step 2-2: 3중량 세트 선택

weight_event가 4개 이상일 때 3개를 선택해야 한다:

1. **산술 탐색**: 모든 조합에서 A - B = C (허용 오차 ±1)를 만족하는 트리플 검색
   - 도메인 제약: A(gross) = 트리플 내 최댓값
   - 호환성 검사: 라벨로 이미 확정된 역할과 충돌하는 트리플은 건너뜀
2. **산술 없음**: 라벨 점수 기반 상위 3개 선택 + WARNING
3. **미선택 weight_event**: `extra_weights[]`에 보관

#### Step 2-3: 중량 역할 할당 (2단계)

**1순위 — 라벨 매칭 (score ≥ 85)**

```
"총중량" 라벨이 붙은 중량 → gross_weight 확정 (inferred_by=LABEL)
"공차중량" 라벨 → tare_weight 확정
"실중량" 라벨 → net_weight 확정
```

**2순위 — 산술 관계**

```
A - B = C (A = max = gross, B = tare, C = net)
→ inferred_by=ARITHMETIC
```

- 허용 오차: ±1 (OCR 1자리 오인식 대응)
- 호환성 검사: 라벨 매칭으로 이미 확정된 역할과 충돌하면 해당 트리플 건너뜀

**UNRESOLVED**: 위 두 단계로 결정 불가 시 억지 할당하지 않음
- `weight_roles_unresolved: true`
- `resolution_hint` 제공 (미할당 중량 목록)

#### 기타 필드 처리

| 라인 유형 | 처리 |
|-----------|------|
| `LABEL_VALUE_LINE` | 콜론 기준 라벨:값 분리 → 라벨 퍼지 매칭 → 필드 식별 |
| `TIMESTAMP_LINE` | `issued_at` 필드로 추출 |
| `DATE_LINE` | `measurement_date` 필드로 추출 |
| `ISSUER_LINE` | `issuer` 필드로 추출 |
| `ETC_LINE` | 문서 유형 탐지 (계량증명서, 계근표 등) |

`serial_number`: `measurement_date` 라벨 매칭 라인에서만 날짜 직후 3~6자리 숫자를 추출.

#### inferred_by와 confidence

| inferred_by | assignment_confidence | status |
|-------------|----------------------|--------|
| `LABEL` | 1.0 | OK |
| `ARITHMETIC` | 0.9 | OK |

OCR confidence가 낮으면(< 0.7) inferred_by와 무관하게 WARNING 추가.

---

### 6.4 Normalizer (223줄) — 값 정규화

#### 역할

추출된 필드 값을 표준 포맷으로 정규화한다. `raw_value`는 항상 원본을 보존한다.

#### 정규화 규칙

| 필드 | 입력 예시 | 출력 | 규칙 |
|------|-----------|------|------|
| 날짜 | `2026-02-02-00004` | `2026-02-02` | 일련번호 분리 |
| 시간 | `05시26분`, `05:26`, `5:26:18` | `05:26:00`, `05:26:18` | 한글 시/분 → 콜론, 분만 있으면 `:00` 패딩, 1자리 시 → 0패딩 |
| 중량 | `12,480`, `5 010` | `12480`, `5010` | 콤마/공백 제거 |
| 단위 | `kg` | `kg` | 소문자 `"kg"` 통일 |

---

### 6.5 Validator (302줄) — 교차 검증

#### 역할

추출·정규화된 필드 간의 일관성을 검증하고, 문서 레벨 판정 플래그를 설정한다.

#### 검증 규칙

| # | 규칙 | 실패 시 |
|---|------|---------|
| 1 | 총중량 - 공차 = 실중량 | 세 필드 모두 WARNING(HIGH) |
| 2 | 중량 양수 검증 | 0이면 WARNING, 음수면 ERROR |
| 3 | 총중량 > 공차, 총중량 > 실중량 | WARNING |
| 4 | 날짜 유효성 (13월, 미래 날짜 등) | WARNING |
| 5 | 필수 필드 (2계층) | 아래 참조 |

#### 필수 필드 2계층

```
(1) 문서 성립 필수 (없으면 is_consistent = false):
    - measurement_date
    - vehicle_number

(2) 자동처리 필수 (없으면 is_actionable = false):
    - gross_weight, tare_weight, net_weight 3개 모두
    - UNRESOLVED 없음
    - ERROR 없음
```

#### 문서 레벨 판정

- `is_consistent`: 내부 일관성 (산술, 날짜 유효성)
- `is_actionable`: 자동 처리 가능 (필수 필드 OK + UNRESOLVED 없음 + ERROR 없음)

---

## 7. 라벨 매칭 시스템

### AliasMatchingUtils (75줄)

`String.contains()` 기반의 경량 매칭 시스템:

```
score(input, alias):
  정규화된 입력 == 정규화된 별칭 → 85 (확정)
  입력이 별칭을 포함 or 별칭이 입력을 포함 → 50 (약매칭)
  그 외 → 0
```

정규화: 공백 제거 + 소문자 변환

유틸 메서드:
- `matchesAny(input, aliases)`: 별칭 목록 중 하나라도 매칭되면 true
- `bestMatch(input, aliasMap)`: 가장 높은 점수의 필드명 반환
- `bestScoreForRole(input, roleAliases)`: 특정 역할의 별칭들 중 최고 점수

### 별칭 사전 (FieldAliases, 36줄)

```java
// FIELD_ALIASES — 일반 필드
"measurement_date" → ["계량일자", "날짜", "일시"]
"vehicle_number"   → ["차량번호", "차번호", "차량No"]
"customer"         → ["거래처", "상호", "회사명"]
"product_name"     → ["품명"]
"category"         → ["구분"]

// WEIGHT_ALIASES — 중량 필드
"gross_weight" → ["총중량"]
"tare_weight"  → ["공차중량", "차중량"]
"net_weight"   → ["실중량"]

// DOC_TYPE_ALIASES — 문서 유형
["계량증명서", "계근표", "계량확인서", "계량증명표"]
```

새 양식 대응 시 이 사전에 별칭만 추가하면 됨.

---

## 8. 데이터 모델

### 입력 스키마 (OcrInput)

```json
{
  "text": "전체 텍스트 (lines가 없을 때 fallback)",
  "lines": [
    {
      "text": "라인 텍스트",
      "words": [
        { "text": "단어", "confidence": 0.95 }
      ]
    }
  ]
}
```

`pages[].lines[]` 형식도 자동 감지 지원.

### 출력 스키마 (ParsedDocument → JSON)

```json
{
  "document_type": "계량증명서",
  "source_file": "sample_01.json",
  "fields": {
    "measurement_date": {
      "value": "2026-02-02",
      "raw_label": "계량일자",
      "raw_value": "2026-02-02",
      "ocr_confidence": 0.95,
      "status": "OK",
      "source_line_index": 1
    },
    "gross_weight": {
      "value": 12480,
      "unit": "kg",
      "time": "05:26:18",
      "raw_label": "품종명랑",
      "raw_value": "12,480",
      "ocr_confidence": 0.92,
      "assignment_confidence": 1.0,
      "inferred_by": "LABEL",
      "status": "OK",
      "source_line_index": 4
    },
    "tare_weight": { "..." },
    "net_weight": { "..." }
  },
  "unassigned_weights": [],
  "extra_weights": [],
  "resolution_hint": null,
  "validation": {
    "is_consistent": true,
    "is_actionable": true,
    "weight_roles_unresolved": false,
    "messages": []
  }
}
```

### Java 모델 클래스

| 클래스 | 타입 | 주요 필드 |
|--------|------|-----------|
| `BaseField` | record | value, rawLabel, rawValue, ocrConfidence, status, severity, message, sourceLineIndex |
| `WeightField` | record | BaseField 필드 + unit, time, assignmentConfidence, inferredBy |
| `ParsedDocument` | record | documentType, sourceFile, fields, unassignedWeights, extraWeights, resolutionHint, validation |
| `ValidationResult` | record | isConsistent, isActionable, weightRolesUnresolved, messages |
| `ProcessedLine` | record | text, lineIndex, ocrConfidence, isNoiseCandidate |
| `ClassifiedLine` | record | ProcessedLine 래핑 + type |
| `WeightCandidate` | record | weight, time, unit, rawLabel, ocrConfidence, lineIndex |
| `ResolutionHint` | record | unassignedWeights, message |

### Enum 클래스

| Enum | 값 | 용도 |
|------|-----|------|
| `FieldStatus` | OK, WARNING, ERROR, MISSING, UNRESOLVED | 필드 상태 |
| `InferredBy` | LABEL, ARITHMETIC, HEURISTIC | 중량 역할 추론 경로 |
| `Severity` | LOW, MEDIUM, HIGH | WARNING 심각도 |
| `LineType` | WEIGHT_EVENT, LABEL_VALUE_LINE, TIMESTAMP_LINE, GPS_LINE, DATE_LINE, ISSUER_LINE, ETC_LINE | 라인 분류 유형 |

---

## 9. 임계값 (Thresholds.java)

모든 매직넘버를 한 곳에 집중하여 튜닝과 유지보수를 용이하게 한다.

| 상수 | 값 | 용도 |
|------|-----|------|
| `NOISE_DELETE_THRESHOLD` | 0.1 | 극단적 노이즈 삭제 기준 |
| `NOISE_MARK_THRESHOLD` | 0.3 | 노이즈 후보 마킹 기준 |
| `DEFAULT_CONFIDENCE` | 1.0 | word 없을 때 기본값 |
| `OCR_CONF_WARNING` | 0.7 | OCR 신뢰도 경고 |
| `FUZZY_LABEL_CONFIRM` | 85 | 라벨 확정 점수 |
| `FUZZY_LABEL_WEAK` | 50 | 라벨 약매칭 점수 |
| `WEIGHT_DIGITS_MIN` | 3 | 중량 숫자 최소 자릿수 |
| `WEIGHT_DIGITS_MAX` | 6 | 중량 숫자 최대 자릿수 |
| `ASSIGNMENT_CONF_LABEL` | 1.0 | 라벨 할당 신뢰도 |
| `ASSIGNMENT_CONF_ARITHMETIC` | 0.9 | 산술 할당 신뢰도 |
| `DOC_TYPE_SEARCH_LIMIT` | 3 | 문서 유형 탐색 라인 수 |

---

## 10. 프로젝트 통계

| 구분 | 파일 수 | 줄 수 |
|------|---------|-------|
| 메인 소스 | 23 | ~2,100 |
| 테스트 | 8 | ~2,080 |
| **합계** | **31** | **~4,180** |

가장 큰 파일: FieldAssigner.java (718줄) — 중량 역할 할당 로직
테스트 대 코드 비율: 약 1:1

---

## 11. CLI 사용법

```bash
# 단일 파일 파싱 (결과를 stdout 출력)
java -jar weighbridge-parser.jar input.json

# 결과를 파일로 저장
java -jar weighbridge-parser.jar input.json output.json
```

---

## 12. 설계 결정 및 근거

| 결정 | 근거 |
|------|------|
| Value-First 분류 | OCR 라벨 오인식이 잦아 값 패턴이 더 신뢰할 수 있음 |
| 라벨 > 산술 > UNRESOLVED (2단계 할당) | 라벨이 가장 확실한 근거, 산술은 보조, 불확실하면 미확정 |
| 산술 트리플 호환성 검사 | 라벨로 이미 확정된 역할과 충돌하는 트리플 방지 |
| 노이즈 마킹 (삭제 아닌) | 거래처, 회사명 등은 원래 OCR confidence가 낮게 나오므로 삭제하면 정보 손실 |
| String.contains() 매칭 | 한국어 계근지 라벨은 짧고 명확하여 Levenshtein/FuzzyScore 불필요 |
| 이중 confidence 분리 | OCR 읽기 정확도와 필드 할당 정확도는 독립적 지표 |
| 이중 검증 플래그 분리 | "내부 일관성 OK인데 자동 처리 불가" 같은 상태 표현 가능 |
| 허용 오차 ±1 | OCR에서 1자리 오인식(0↔8, 1↔7 등)이 빈번 |
| 병합 조건 엄격화 | "숫자로 시작"은 너무 넓음 — 전화번호, 좌표, 일련번호 오병합 방지 |
| UNRESOLVED에 severity 없음 | status 자체가 충분한 신호, `is_actionable=false`로 문서 레벨에서 표현 |

---

## 13. 테스트 구조

| 테스트 파일 | 줄 수 | 테스트 대상 |
|-------------|-------|-------------|
| `IntegrationTest` | 387 | 4종 샘플 전체 파이프라인 통과 |
| `FieldAssignerTest` | 506 | 중량 파싱, 트리플 선택, 역할 할당, UNRESOLVED |
| `NormalizerTest` | 274 | 날짜/시간/중량/단위 정규화 엣지 케이스 |
| `ValidatorTest` | 354 | 산술 검증, 날짜 유효성, 필수 필드 |
| `ExtractorTest` | 176 | 라인 분류, issuer 과탐 방지 |
| `PreprocessorTest` | 205 | 공백 정규화, 노이즈 마킹, 라인 병합 |
| `OutputTest` | 95 | JSON 직렬화 |
| `AliasMatchingUtilsTest` | 83 | 매칭 점수 계산 |

---

## 14. 확장 포인트

| 확장 | 수정 대상 |
|------|-----------|
| 새 양식 대응 | `FieldAliases`에 별칭 추가 |
| 임계값 튜닝 | `Thresholds` 상수 조정 |
| 새 라인 유형 | `LineType` enum 추가 + `Extractor.classifySingleLine()` 수정 |
| 새 출력 포맷 | `JsonWriter` 패턴 참고하여 별도 Writer 구현 |
