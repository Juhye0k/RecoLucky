# Weighbridge Parser - CLAUDE.md

## 프로젝트 개요

계근지(계량증명서) OCR 결과 JSON을 입력받아, 업무 필드(날짜, 차량번호, 중량 등)를 추출·정규화하여 구조화된 JSON으로 출력하는 **규칙 기반 파싱 시스템**. OCR 노이즈(오탈자, 라벨 누락, 포맷 불규칙)에 견고하게 대응하며, 새 양식 추가 시 별칭 사전만 수정하면 확장 가능한 구조.

## 기술 스택

- **언어**: Java 17+ (record, switch expression)
- **빌드**: Gradle (Groovy DSL)
- **JSON**: Jackson 2.17 (`ObjectMapper`, `@JsonProperty`, `jackson-datatype-jsr310`)
- **라벨 매칭**: `String.contains()` 기반 (완전일치 85점 / 포함 50점)
- **테스트**: JUnit 5 + AssertJ

## 핵심 설계 원칙

1. **Value-First Classification**: 라벨보다 값 패턴(kg 단위, 날짜, GPS 등)을 먼저 판별하여 라인 유형 결정. 라벨은 세부 필드 구분 힌트로만 사용
2. **엄격한 중량 확정**: kg 단위가 사실상 필수. time+정수만으로는 약후보(candidate)로만 취급
3. **미확정 허용(UNRESOLVED)**: 공차/실중량 구분 불확실 시 억지 할당 금지. `weight_roles_unresolved: true`로 출력
4. **추론 경로 기록(Provenance)**: 각 중량 필드에 `inferred_by`(LABEL/ARITHMETIC) 기록
5. **노이즈 마킹**: 저신뢰 토큰을 삭제하지 않고 `is_noise_candidate` 플래그만 부여
6. **이중 검증 플래그**: `is_consistent`(내부 일관성) + `is_actionable`(자동 처리 가능 여부) 분리
7. **confidence 분리**: `ocr_confidence`(OCR 읽기 정확도) + `assignment_confidence`(필드 할당 정확도) 독립 관리

## 파이프라인 아키텍처

```
OCR JSON → Preprocessor → Extractor → FieldAssigner → Normalizer → Validator → JSON Output
```

### 1. Preprocessor (텍스트 전처리) — `Preprocessor.java`
- **입력 소스**: `lines[]` 우선 (word별 confidence 활용), `text`는 fallback
- **Step 1**: 라인 단위 분리 (`lines[]` 순회, words를 공백 join)
- **Step 2**: 한글 자모 사이 단일 공백 제거 (`거 래 처` → `거래처`)
- **Step 3**: 노이즈 마킹 (삭제 아닌 마킹)
  - confidence < 0.1 + 단독 쓰레기 토큰 → 삭제
  - 라인 내 최저 word confidence < 0.3 → `is_noise_candidate: true`
- **Step 4**: 라인 병합
  - 1글자+콜론 라벨 조각 제거 (예: `명:`)
  - 중량 라벨(콜론 종료 + weight alias 매칭) + 중량 값(kg 포함) → 병합
  - 병합 금지: 전화번호, GPS 좌표, TEL/FAX 라벨

### 2. Extractor (라인 분류) — `Extractor.java`
- 값 패턴 기반 라인 분류 (Value-First)
- `label_delimiter_colon` 판정: 시간 내 콜론은 사전 제거, 전각 콜론(：) 허용
- 분류 우선순위:
  - 콜론 + kg → `WEIGHT_EVENT`
  - 콜론 + kg 없음 → `LABEL_VALUE_LINE`
  - 콜론 없음 → TIMESTAMP > GPS > WEIGHT_EVENT(kg+digits) > 약후보(time+digits) > DATE > ISSUER > ETC
- **weight_event 확정**: kg/㎏/KG + 3~6자리 정수 + guard rule 통과 ("원/kg", "kg당", "단가" 제외)
- **OCR 깨진 단위 인식**: k9, kq, K G, k g, KG, ㎏, Kg → 모두 kg
- **약후보 승격 (Path A)**: 문서 내 확정 kg ≥1 + ±2 라인에 중량 라벨 힌트(≥50점) → 승격. 미충족 → ETC_LINE 강등
- **issuer 판정**: (주)/(株)/㈜/Co./C&S 패턴 + 주소/전화/우편번호/negative lexicon 제외

### 3. FieldAssigner (필드 할당) — `FieldAssigner.java`
- **Step 2-1**: weight_event에서 시간·중량값 분리, WeightCandidate 생성
- **Step 2-2**: weight_event ≥ 4개 → 3중량 세트 선택 (`selectTriple`)
  - 산술 탐색: A - B = C (A = 최댓값 = gross), 기존 라벨 할당과 호환되는 트리플만 채택
  - 산술 없으면: 라벨 매칭 스코어 상위 3개 선택 (fallback)
  - 미선택 → `extra_weights[]` 보관
- **Step 2-3**: 총/공차/실 역할 할당
  - **1순위**: 라벨 매칭 (≥85점 CONFIRM → 해당 역할 확정)
  - **2순위**: 산술 관계 (A - B = C, A = gross) — 라벨 할당과 충돌 시 해당 트리플 skip
  - 미할당 잔여 → **UNRESOLVED** (heuristic 점수 없이 바로)
- **label_value_line 처리**: 콜론 기준 분리 → 별칭 매칭 → 필드 할당
  - serial_number: measurement_date 라인에서 날짜 직후 3~6자리 숫자 추출
- **기타 라인**: ISSUER_LINE → issuer, TIMESTAMP_LINE → issued_at, GPS_LINE → gps_coordinates, DATE_LINE/ETC_LINE → 별칭 매칭
- **confidence**: ocr_confidence = 값 토큰 min(confidence), assignment_confidence = label→1.0 / arithmetic→0.9

### 4. Normalizer (값 정규화) — `Normalizer.java`
- **날짜**: `YYYY-MM-DD` (구분자 `.`/`/` → `-` 통일)
- **시간**: `HH:MM:SS` (분만 있으면 `:00` 패딩, 한글 `시/분` → 콜론)
- **타임스탬프**: 날짜+시간 분리 후 각각 정규화
- **중량**: 정수 int (콤마/공백 제거, 소수점 반올림)
- **단위**: 소문자 `"kg"` 통일
- `raw_value`는 항상 원본 보존

### 5. Validator (교차 검증) — `Validator.java`
- **규칙 1**: 중량 산술 — gross - tare = net (불일치 시 세 필드 모두 WARNING(HIGH))
- **규칙 2**: 중량 양수 — 0이면 WARNING(MEDIUM), 음수면 ERROR
- **규칙 3**: 총중량 최대값 — gross < tare 또는 gross < net이면 ERROR
- **규칙 4**: 날짜 유효성 — 파싱 불가(13월 등) → ERROR, 미래 날짜 → WARNING(MEDIUM)
- **규칙 5**: 필수 필드 2계층
  - 문서 성립 필수 (없으면 `is_consistent=false`): measurement_date, vehicle_number
  - 자동처리 필수 (없으면 `is_actionable=false`): 3 중량 필드 (MISSING/UNRESOLVED 포함)
  - 권장 (없으면 WARNING만): customer, issuer
- **문서 레벨 판정**:
  - `is_consistent`: 산술 통과 + 필수 필드 OK + 날짜 유효 + ERROR 없음
  - `is_actionable`: is_consistent + 자동처리 필수 OK + UNRESOLVED 없음

## 모듈 구조

```
weighbridge-parser/
├── build.gradle
├── src/main/java/com/weighbridge/parser/
│   ├── WeighbridgeParserApplication.java    # CLI: main(args[0]=input, args[1]=output)
│   ├── config/
│   │   ├── AliasMatchingUtils.java          # String.contains() 기반 매칭
│   │   ├── FieldAliases.java                # 라벨 별칭 사전
│   │   └── Thresholds.java                  # 임계값 상수 12개
│   ├── model/
│   │   ├── OcrInput.java                    # OCR JSON 입력 (text, lines[], words[])
│   │   ├── BaseField.java                   # 일반 필드 record
│   │   ├── WeightField.java                 # 중량 필드 record (+unit, time, assignmentConfidence, inferredBy)
│   │   ├── ParsedDocument.java              # 파싱 결과 최상위 record
│   │   ├── ValidationResult.java            # 검증 결과 record
│   │   ├── ResolutionHint.java              # UNRESOLVED 수동 확인 힌트
│   │   ├── ProcessedLine.java               # 전처리된 라인 record
│   │   ├── FieldStatus.java                 # enum: OK, WARNING, ERROR, MISSING, UNRESOLVED
│   │   ├── InferredBy.java                  # enum: LABEL, ARITHMETIC, HEURISTIC
│   │   └── Severity.java                    # enum: LOW, MEDIUM, HIGH
│   ├── pipeline/
│   │   ├── Preprocessor.java                # Step 1: 전처리 (공백·노이즈·병합)
│   │   ├── Extractor.java                   # Step 2-1: 값 패턴 기반 라인 분류
│   │   ├── FieldAssigner.java               # Step 2-2: 필드 할당 (라벨+산술)
│   │   ├── Normalizer.java                  # Step 3: 값 정규화
│   │   ├── Validator.java                   # Step 4: 교차 검증
│   │   ├── ClassifiedLine.java              # 분류된 라인 record
│   │   ├── LineType.java                    # enum: 7종 라인 유형
│   │   └── WeightCandidate.java             # 중량 후보 record
│   └── output/
│       └── JsonWriter.java                  # JSON 직렬화 (INDENT_OUTPUT)
└── src/test/
    ├── java/com/weighbridge/parser/
    │   ├── PreprocessorTest.java            # 전처리 단위 테스트
    │   ├── ExtractorTest.java               # 라인 분류 테스트
    │   ├── FieldAssignerTest.java           # 필드 할당 테스트
    │   ├── NormalizerTest.java              # 정규화 테스트
    │   ├── ValidatorTest.java               # 검증 테스트
    │   ├── AliasMatchingUtilsTest.java      # 별칭 매칭 테스트
    │   ├── IntegrationTest.java             # 4종 샘플 통합 테스트
    │   └── OutputTest.java                  # JSON 출력 테스트
    └── resources/fixtures/
        ├── sample_01.json                   # 계량증명서 (표준)
        ├── sample_02.json                   # 계근표 (공백 분리 중량)
        ├── sample_03.json                   # 계량확인서 (한글 시간, TEL)
        └── sample_04.json                   # 계량증명표 (괄호 시간)
```

## 출력 스키마

### BaseField (일반 필드)
`value`, `raw_label`, `raw_value`, `ocr_confidence`, `status`, `severity`(WARNING만), `message`, `source_line_index`

### WeightField (중량 필드)
BaseField + `unit`, `time`, `assignment_confidence`, `inferred_by`

### 문서 레벨
`document_type`, `source_file`, `fields{}`, `unassigned_weights[]`, `extra_weights[]`, `resolution_hint`, `validation{}`

## 별칭 사전 (확장 포인트) — `FieldAliases.java`

```java
// FIELD_ALIASES
"measurement_date" → ["계량일자", "날짜", "일시", "계량일"]
"vehicle_number"   → ["차량번호", "차번호", "차량No"]
"customer"         → ["거래처", "상호", "회사명"]
"product_name"     → ["품명", "품종명", "품목"]
"category"         → ["구분"]

// WEIGHT_ALIASES
"gross_weight" → ["총중량"]
"tare_weight"  → ["공차중량", "차중량"]
"net_weight"   → ["실중량"]

// DOC_TYPE_ALIASES
["계량증명서", "계근표", "계량확인서", "계량증명표", "계근증명서"]
```

## 임계값 — `Thresholds.java`

| 상수 | 값 | 용도 |
|------|-----|------|
| NOISE_DELETE_THRESHOLD | 0.1 | 삭제 대상 |
| NOISE_MARK_THRESHOLD | 0.3 | 노이즈 마킹 |
| DEFAULT_CONFIDENCE | 0.95 | word 없을 때 기본값 |
| OCR_CONF_WARNING | 0.7 | OCR 저신뢰 WARNING 기준 |
| FUZZY_LABEL_CONFIRM | 85 | 라벨 확정 (완전일치) |
| FUZZY_LABEL_WEAK | 50 | 라벨 약매칭 (포함 관계) |
| WEIGHT_DIGITS_MIN | 3 | 중량 최소 자릿수 |
| WEIGHT_DIGITS_MAX | 6 | 중량 최대 자릿수 |
| ASSIGNMENT_CONF_LABEL | 1.0 | 라벨 할당 신뢰도 |
| ASSIGNMENT_CONF_ARITHMETIC | 0.9 | 산술 할당 신뢰도 |
| DOC_TYPE_SEARCH_LIMIT | 3 | 문서 유형 탐색 라인 수 |

## CLI 사용법

```bash
# 단일 파일 (결과를 stdout 출력)
java -jar weighbridge-parser.jar input.json

# 단일 파일 (결과를 파일로 저장)
java -jar weighbridge-parser.jar input.json output.json
```

## 주의사항

- **매직넘버 금지**: 모든 임계값은 `Thresholds.java`에 집중
- **시간 순서는 확정 근거로 사용 금지**: 참고 자료로만 활용 (업체마다 관행 다름)
- **guard rule**: "원/kg", "kg당", "단가" 포함 라인은 weight_event에서 제외
- **삭제보다 마킹**: 저신뢰 토큰은 삭제하지 않고 플래그만 부여
- **UNRESOLVED는 severity 없음**: 문서 레벨 `is_actionable=false`로 표현
- **ERROR도 severity 없음**: status 자체가 충분한 신호
- **산술 트리플 호환성**: 기존 라벨 할당과 충돌하는 산술 트리플은 skip

## 비목표 (스코프 밖)

- OCR 엔진 자체 개발/교체
- 이미지 전처리
- 웹 UI / API 서버
- ML 기반 NER 모델 학습
- 실시간 스트리밍 처리
