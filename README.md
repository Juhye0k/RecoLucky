# Weighbridge Parser

계근지(계량증명서) OCR 결과 JSON을 입력받아, 업무 필드를 추출하고 정규화하여 구조화된 JSON으로 출력하는 **규칙 기반 파싱 시스템**입니다.

> OCR 노이즈(오탈자, 라벨 누락, 포맷 불규칙)에 견고하게 대응하며,
> 새 양식 추가 시 별칭 사전만 수정하면 확장 가능한 구조를 목표로 설계했습니다.

---

## 목차

1. [빠른 시작](#빠른-시작)
2. [실행 방법](#실행-방법)
3. [파이프라인 아키텍처](#파이프라인-아키텍처)
4. [설계 및 주요 가정](#설계-및-주요-가정)
5. [한계 및 개선 아이디어](#한계-및-개선-아이디어)
6. [출력 예시](#출력-예시)
7. [프로젝트 구조](#프로젝트-구조)
8. [테스트](#테스트)

---

## 빠른 시작

### 환경

- **Java 17+**, **Gradle 8.x** (Wrapper 포함)
- 런타임 의존성: Jackson 2.17 (JSON 직렬화)
- 테스트: JUnit 5 + AssertJ

### 빌드 및 실행

```bash
# 빌드
./gradlew build

# 실행 (stdout 출력)
./gradlew run --args="src/test/resources/fixtures/sample_01.json"

# 실행 (파일 저장)
./gradlew run --args="src/test/resources/fixtures/sample_01.json output.json"
```

### 입력 형식

```json
{
  "text": "계 량 증 명 서\n계량일자: 2026-02-02 ...",
  "lines": [
    {
      "text": "계 량 증 명 서",
      "words": [
        { "text": "계", "confidence": 0.98 },
        { "text": "량", "confidence": 0.97 }
      ]
    }
  ]
}
```

`lines[]`가 있으면 word별 confidence를 활용하고, 없으면 `text`를 줄바꿈 기준으로 fallback 처리합니다.

---

## 실행 방법

> **`input.json`이란?** OCR 엔진(Google Document AI, Naver Clova OCR 등)이 계량증명서 이미지를 읽고 출력한 JSON 파일입니다. 이 파서는 OCR 결과를 입력받아 구조화된 데이터로 변환합니다.

### 1. Gradle Wrapper로 실행 (개발 중)

#### stdout 출력 (결과를 화면에 표시)

```bash
# input.json = OCR 엔진이 출력한 JSON 파일
./gradlew run --args="input.json"
```

**로그와 JSON이 함께 출력됩니다:**

```
> Task :run

20:15:23.313 INFO  [WeighbridgeParserApplication] - 파이프라인 시작: input.json
20:15:23.525 INFO  [Preprocessor] - 전처리 완료: 입력 13줄 → 최종 11줄 (노이즈 삭제 0, 마킹 1, 병합 2건)
20:15:23.531 INFO  [Extractor] - 라인 분류 완료: ISSUER_LINE=1, TIMESTAMP_LINE=1, ...
20:15:23.569 INFO  [FieldAssigner] - 필드 할당 완료: 필드=[gross_weight, tare_weight, ...]
20:15:23.572 INFO  [Normalizer] - 정규화 완료: 10개 필드
20:15:23.574 INFO  [Validator] - 검증 완료: is_consistent=true, is_actionable=true
{
  "document_type": "계량증명서",
  "source_file": "input.json",
  "fields": {
    "gross_weight": { "value": 12480, "unit": "kg", ... },
    ...
  }
}
20:15:23.619 INFO  [WeighbridgeParserApplication] - 파이프라인 완료: stdout 출력
```

**로그 없이 JSON만 출력하려면:**

```bash
./gradlew run --args="sample_01.json" --quiet
```

#### 파일 저장 (결과를 파일로 저장)

```bash
./gradlew run --args="sample_01.json output.json"
```

**출력:**
```
> Task :run

20:15:23.313 INFO  [...] - 파이프라인 시작: input.json
...
20:15:23.619 INFO  [...] - 파이프라인 완료: 파일 출력 → output.json
Written: output.json
```

`output.json` 파일이 생성됩니다.

#### 다양한 입력 경로

```bash
# 상대 경로
./gradlew run --args="examples/sample_01_input.json"

# 절대 경로
./gradlew run --args="/full/path/to/ocr_output.json"

# 테스트 fixtures 사용
./gradlew run --args="src/test/resources/fixtures/sample_01.json output.json"
```

### 2. JAR 파일로 실행 (배포용)

#### JAR 빌드

```bash
./gradlew build

# 생성 위치
# build/libs/weighbridge-parser-1.0-SNAPSHOT.jar
```

#### JAR 실행

```bash
# stdout 출력
java -jar build/libs/weighbridge-parser-1.0-SNAPSHOT.jar input.json

# 파일 저장
java -jar build/libs/weighbridge-parser-1.0-SNAPSHOT.jar input.json output.json
```



### 3. Windows 환경 (WSL/cmd.exe)

#### WSL에서 실행

```bash
# gradlew는 WSL 환경에서 Java가 없으면 실패
# gradlew.bat을 cmd.exe로 실행

cd "/mnt/c/Users/kyoun/OneDrive/바탕 화면/프로젝트/ai/untitled1"
cmd.exe /c "gradlew.bat run --args=\"input.json\""
```

#### Windows CMD에서 실행

```cmd
gradlew.bat run --args="input.json output.json"
```

#### PowerShell에서 실행

```powershell
.\gradlew.bat run --args="input.json output.json"
```

### 4. 실전 예시

#### 예시 1: examples 디렉토리 샘플 처리

```bash
# 실제 OCR API 응답 형식 파일 사용
./gradlew run --args="examples/sample_01_input.json result_01.json"

# 결과 확인
cat result_01.json | jq .validation
```

출력:
```json
{
  "is_consistent": true,
  "is_actionable": true,
  "weight_arithmetic": {
    "passed": true,
    "gross": 12480,
    "tare": 7470,
    "net": 5010,
    "expected_net": 5010,
    "delta": 0
  }
}
```

#### 예시 2: 배치 처리 (여러 파일)

```bash
# Bash 반복문
for file in examples/*.json; do
  output="output/$(basename $file .json)_parsed.json"
  ./gradlew run --args="$file $output" --quiet
  echo "Processed: $file → $output"
done
```



#### 예시 4: 파이프라인 연결 (전체 흐름)

```bash
# Step 1: 계량증명서 이미지를 OCR API로 전송
curl -X POST https://ocr-api.example.com/parse \
  -F "file=@weighbridge_image.jpg" \
  -o ocr_output.json

# ocr_output.json 내용 예시:
# {
#   "text": "계량증명서\n계량일자: 2026-02-02\n...",
#   "lines": [...]
# }

# Step 2: OCR 결과를 파서로 구조화
./gradlew run --args="ocr_output.json parsed.json" --quiet

# parsed.json 내용 예시:
# {
#   "document_type": "계량증명서",
#   "fields": {
#     "gross_weight": { "value": 12480, "unit": "kg" },
#     ...
#   }
# }

# Step 3: 구조화된 데이터를 데이터베이스/API로 전송
curl -X POST https://api.example.com/weighbridge \
  -H "Content-Type: application/json" \
  -d @parsed.json
```

### 5. 입력 파일 형식 상세


일반적인 OCR 처리 흐름:
```
계량증명서 이미지 (JPG/PNG)
    ↓
OCR API (Google Document AI, Naver Clova, Tesseract 등)
    ↓
OCR 출력 JSON (input.json) ← 이 파서의 입력
    ↓
Weighbridge Parser
    ↓
구조화된 JSON (output.json)
```

#### 최소 형식 (text만)

```json
{
  "text": "계량증명서\n계량일자: 2026-02-02\n차량번호: 8713\n총중량: 12,480 kg\n공차중량: 7,470 kg\n실중량: 5,010 kg"
}
```

**사용 케이스:** 단순 텍스트 추출만 가능한 OCR 엔진 (Tesseract 기본 모드 등)

#### 권장 형식 (lines + words + confidence)

```json
{
  "text": "...",
  "lines": [
    {
      "text": "총중량: 12,480 kg",
      "words": [
        { "text": "총중량:", "confidence": 0.98 },
        { "text": "12,480", "confidence": 0.97 },
        { "text": "kg", "confidence": 0.99 }
      ]
    }
  ]
}
```

**사용 케이스:** 상용 OCR API (Google Document AI, Naver Clova OCR, AWS Textract 등)

**차이:**
- `text`만: 라인 단위 confidence를 알 수 없음 (기본값 0.95 사용)
- `lines[]` + `words[]`: 토큰별 confidence를 활용하여 노이즈 마킹, OCR 신뢰도 경고 가능

#### 실제 OCR API 응답 (Google Document AI)

`examples/sample_01_input.json` 참고 — boundingBox, metadata 등 전체 메타데이터 포함

```json
{
  "apiVersion": "1.1",
  "confidence": 0.9242,
  "metadata": { "pages": [...] },
  "pages": [{
    "text": "계 량 증 명 서 ...",
    "words": [
      {
        "text": "계",
        "confidence": 0.9745,
        "boundingBox": { "vertices": [...] }
      },
      ...
    ],
    "lines": [...]
  }]
}
```

**이 파서는 `boundingBox`, `metadata` 등을 무시하고 `text`와 `lines[]`만 사용합니다.**

### 6. 출력 확인

#### 브라우저에서 확인 (JSON 포맷팅)

```bash
# JSON 파일을 브라우저로 열기 (Chrome/Firefox)
open output.json  # macOS
xdg-open output.json  # Linux
start output.json  # Windows
```

#### 터미널에서 확인 (jq)

```bash
# 예쁘게 출력
cat output.json | jq .

# 특정 필드만 확인
cat output.json | jq .fields.gross_weight
cat output.json | jq .validation
cat output.json | jq '.fields | keys'  # 모든 필드명 나열
```

#### Python으로 확인

```python
import json

with open('output.json') as f:
    doc = json.load(f)

print(f"문서 유형: {doc['document_type']}")
print(f"총중량: {doc['fields']['gross_weight']['value']} kg")
print(f"검증 통과: {doc['validation']['is_actionable']}")
```


---

## 파이프라인 아키텍처

```
OCR JSON ─▶ Preprocessor ─▶ Extractor ─▶ FieldAssigner ─▶ Normalizer ─▶ Validator ─▶ JSON
             전처리           라인 분류      필드 할당        값 정규화      교차 검증
```

각 단계는 **입력과 출력이 명확한 순수 함수**로 설계하여, 단계별 독립 테스트와 디버깅이 가능합니다.

### 1. Preprocessor

OCR 원문의 노이즈를 정리합니다.

| 처리 | 예시 |
|------|------|
| 한글 자모 사이 공백 제거 | `거 래 처` → `거래처` |
| 저신뢰 토큰 마킹 | confidence < 0.3 → `is_noise_candidate` 플래그 |
| 분리된 라인 병합 | `중량:` + `7,470 kg` → `중량: 7,470 kg` |

### 2. Extractor

**Value-First** 원칙으로 라인을 분류합니다.

```
콜론 있음?
├─ YES + kg 단위 → WEIGHT_EVENT
├─ YES + kg 없음 → LABEL_VALUE_LINE
└─ NO  → TIMESTAMP > GPS > WEIGHT_EVENT > DATE > ISSUER > ETC
```

- OCR이 깨뜨린 단위(`k9`, `kq`, `K G`, `㎏`)도 모두 kg로 인식
- 단가 정보(`원/kg`, `kg당`)는 guard rule로 제외

### 3. FieldAssigner

중량 역할(총/공차/실)을 2단계로 할당합니다.

| 우선순위 | 방법 | assignment_confidence |
|---------|------|-----------------------|
| 1순위 | 라벨 매칭 (점수 85 이상) | 1.0 |
| 2순위 | 산술 관계 (A - B = C) | 0.9 |
| fallback | **UNRESOLVED** | - |

중량 후보가 4개 이상이면 산술 트리플(A - B = C)을 탐색하여 3개를 선택하고, 나머지는 `extra_weights[]`에 보관합니다.

### 4. Normalizer

| 대상 | 변환 | 예시 |
|------|------|------|
| 날짜 | `YYYY-MM-DD` | `2025.12.01` → `2025-12-01` |
| 시간 | `HH:MM:SS` | `11시 33분` → `11:33:00` |
| 중량 | 콤마/공백 제거 → 정수 | `12,480` → `12480` |
| 단위 | 소문자 `"kg"` 통일 | `㎏`, `KG`, `k9` → `kg` |

### 5. Validator

| 규칙 | 위반 시 |
|------|--------|
| 총중량 - 공차중량 = 실중량 | 세 필드 모두 WARNING |
| 중량 > 0 | 0이면 WARNING, 음수면 ERROR |
| 총중량이 최댓값 | 아니면 WARNING |
| 날짜 유효성 (13월, 미래) | WARNING / ERROR |
| 필수 필드 존재 | `is_consistent` / `is_actionable` 판정 |

---

## 설계 및 주요 가정

### 핵심 설계 결정

#### 1. 라벨보다 값 패턴을 먼저 본다 (Value-First Classification)

**문제**: OCR은 라벨을 자주 깨뜨립니다. `총중량`이 `총중랑`이나 `총 중 량`으로 읽히면, 라벨 매칭에만 의존하는 시스템은 해당 라인 자체를 놓치게 됩니다.

**결정**: 라인에 `kg` 단위 + 3~6자리 숫자가 있으면 먼저 `WEIGHT_EVENT`로 분류하고, 라벨은 세부 역할(총/공차/실) 구분의 **힌트**로만 사용합니다. 이 방식으로 라벨이 완전히 깨져도 중량 데이터 자체는 잃지 않습니다.

#### 2. 모르면 모른다고 말한다 (UNRESOLVED 허용)

**문제**: 공차와 실중량의 라벨이 둘 다 깨져 있고 산술로도 구분이 안 되는 경우, 시스템이 임의로 하나를 골라 할당하면 후속 업무에서 잘못된 데이터가 그대로 사용됩니다.

**결정**: 확신 없이 억지 할당하는 대신, `status: UNRESOLVED`로 출력하고 `resolution_hint`에 수동 확인 단서를 제공합니다. 이 결정으로 문서 레벨 `is_actionable`이 false가 되어 자동 처리 대상에서 자연스럽게 제외됩니다. **정확하지 않은 자동화보다, 정직한 불확실성 표시가 더 안전하다고 판단했습니다.**

#### 3. 삭제하지 않고 마킹한다

**문제**: OCR 신뢰도가 낮은 토큰을 삭제하면 정보 손실이 발생합니다. `거래처: 곰욕환경폐기물`에서 `곰욕`의 confidence가 낮다고 삭제하면, 거래처명 자체를 잃습니다.

**결정**: confidence < 0.3인 토큰은 `is_noise_candidate: true` 플래그만 부여하고, 값 자체는 보존합니다. 출력에 `ocr_confidence`를 함께 제공하여 소비자가 판단할 수 있도록 했습니다.

#### 4. 추론 경로를 기록한다 (Provenance)

각 중량 필드에 `inferred_by` (LABEL / ARITHMETIC)를 기록합니다. 라벨 매칭으로 할당했는지, 산술 관계(A - B = C)로 추론했는지를 투명하게 드러내어 결과를 신뢰할 수 있게 합니다.

#### 5. Confidence를 분리한다

`ocr_confidence`(OCR이 글자를 제대로 읽었는가)와 `assignment_confidence`(올바른 필드에 할당되었는가)는 독립적인 의미를 가집니다. OCR이 정확히 읽었더라도 잘못된 필드에 할당될 수 있고, 그 반대도 가능하므로 두 지표를 분리했습니다.

#### 6. 이중 검증 플래그

`is_consistent`(내부 일관성)과 `is_actionable`(자동 처리 가능 여부)을 분리합니다. 예를 들어, 중량 산술이 맞고 날짜도 유효하지만 차량번호가 없으면 `is_consistent: true, is_actionable: false`입니다. 소비자는 용도에 따라 적절한 플래그를 선택할 수 있습니다.

### 주요 가정

| 가정 | 근거 |
|------|------|
| 입력은 **단일 문서** (1 JSON = 1 계량증명서) | 실무 OCR 파이프라인은 보통 문서 단위로 분리된 결과를 전달 |
| 중량은 **3개 1세트** (총중량 - 공차 = 실중량) | 국내 계근지 표준 양식 |
| **kg 단위가 사실상 필수** | 단위 없는 숫자를 무조건 중량으로 보면 오탐 발생. 약후보로만 취급 후 문맥 조건 충족 시 승격 |
| **시간 순서는 확정 근거가 아님** | 업체마다 계량 순서 관행이 달라, 시간만으로 총/공차를 구분하면 오류 가능 |

### 확장 포인트

새로운 양식을 지원하려면 **코드 수정 없이 별칭 사전만 확장**하면 됩니다.

```java
// FieldAliases.java — 예: "1차중량"을 총중량 별칭으로 추가
"gross_weight" → List.of("총중량", "1차중량")
```

모든 매직 넘버는 `Thresholds.java`에 집중되어 있어, 임계값 튜닝도 한 파일에서 완료됩니다.

---

## 한계 및 개선 아이디어

### 현재 한계

#### 1. 검증 범위의 한계

4종의 샘플 양식(계량증명서, 계근표, 계량확인서, 계량증명표)을 기반으로 개발했습니다. 실제 현장에서는 업체별로 더 다양한 양식이 존재하며, 현재 규칙으로 커버되지 않는 엣지 케이스가 발생할 수 있습니다.

#### 2. 심하게 깨진 라벨

`품종명랑`처럼 라벨 자체가 심하게 변형된 경우, `String.contains()` 기반 매칭으로는 한계가 있습니다. 현재는 Value-First 분류로 값 자체를 잃지 않도록 보완하고 있지만, 역할 배정에서는 UNRESOLVED로 떨어질 수 있습니다.

#### 3. 레이아웃 의존성

OCR가 세로 텍스트를 가로로 읽어 라인 순서가 뒤바뀌거나, 하나의 이미지에 여러 문서가 겹친 경우에는 대응하지 못합니다. 현재 시스템은 OCR가 제공하는 라인 순서를 그대로 신뢰합니다.

#### 4. 순수 규칙 기반

ML 모델을 사용하지 않으므로, 규칙으로 정의되지 않은 패턴에는 대응할 수 없습니다. 새로운 패턴이 나타나면 규칙을 수동으로 추가해야 합니다.

### 개선 아이디어

#### 단기 — 현 구조 내에서 개선

- **양식 커버리지 확대**: 실제 현장 데이터를 수집하고 fixture로 추가하여 규칙의 견고성을 높일 수 있습니다.

#### 중기 — 아키텍처 확장

- **별칭 사전 자동 확장**: 운영 중 확인된 새 라벨을 자동으로 사전에 등록하는 피드백 루프를 구축할 수 있습니다.
- **Confidence 기반 재촬영 요청**: `ocr_confidence`가 극히 낮은 필드가 있으면, 사용자에게 재촬영을 요청하는 흐름을 추가할 수 있습니다.



---

## 출력 예시

### sample_01 파싱 결과 (요약)

```json
{
  "document_type": "계량증명서",
  "source_file": "sample_01.json",
  "fields": {
    "gross_weight":     { "value": 12480, "unit": "kg", "time": "05:26:18", "inferred_by": "arithmetic", "status": "OK" },
    "tare_weight":      { "value": 7470,  "unit": "kg", "time": "05:36:01", "inferred_by": "arithmetic", "status": "OK" },
    "net_weight":       { "value": 5010,  "unit": "kg", "inferred_by": "arithmetic", "status": "OK" },
    "measurement_date": { "value": "2026-02-02", "status": "OK" },
    "serial_number":    { "value": "0016", "status": "OK" },
    "vehicle_number":   { "value": "8713", "status": "OK" },
    "customer":         { "value": "곰욕환경폐기물", "status": "WARNING", "message": "OCR 신뢰도 낮음" },
    "issuer":           { "value": "동우바이오(주)", "status": "OK" }
  },
  "validation": {
    "is_consistent": true,
    "is_actionable": true,
    "weight_arithmetic": { "passed": true, "delta": 0 }
  }
}
```


---
## 프로젝트 구조

```
src/main/java/com/weighbridge/parser/
├── WeighbridgeParserApplication.java   # CLI 진입점
├── config/
│   ├── FieldAliases.java               # 라벨 별칭 사전 (확장 포인트)
│   ├── AliasMatchingUtils.java         # String.contains() 기반 매칭
│   └── Thresholds.java                 # 모든 임계값 상수
├── model/                              # 입출력 데이터 모델 (record)
│   ├── OcrInput.java
│   ├── BaseField.java / WeightField.java
│   ├── ParsedDocument.java
│   └── FieldStatus.java / InferredBy.java / Severity.java
├── pipeline/                           # 5단계 파이프라인
│   ├── Preprocessor.java              # 전처리 (공백·노이즈·병합)
│   ├── Extractor.java                 # Value-First 라인 분류
│   ├── FieldAssigner.java             # 필드 할당 (라벨+산술)
│   ├── Normalizer.java                # 값 정규화
│   └── Validator.java                 # 교차 검증
└── output/
    └── JsonWriter.java                # JSON 직렬화
```

