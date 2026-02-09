# 데이터 흐름 예시 — sample_01.json 추적

`sample_01.json`(계량증명서 표준 양식) 하나를 파이프라인 전체에 통과시키며 각 단계의 입출력을 보여준다.

---

## 0. 원본 OCR 입력

```
Line  0: "계 량 증 명 서"
Line  1: "계량일자: 2026-02-02 0016"
Line  2: "차량번호: 8713"
Line  3: "거 래 처: 곰욕환경폐기물"         ← "곰욕" = OCR 오인식 (conf 0.51)
Line  4: "품종명랑 05:26:18 12,480 kg"     ← "품종명랑" = OCR 오인식 (conf 0.19)
Line  5: "명:"                              ← 깨진 라벨 조각
Line  6: "중 량:"                           ← 라벨만 있는 줄
Line  7: "05:36:01 7,470 kg"               ← 값만 있는 줄
Line  8: "실 중 량: 5,010 kg"
Line  9: "* 위와 같이 계량하였음을 확인함."
Line 10: "동우바이오(주)"
Line 11: "2026-02-02 05:37:55"
Line 12: "37.105317, 127.375673"
```

---

## 1. Preprocessor 출력

`OcrInput` → `List<ProcessedLine>` (13줄 → 11줄)

**Step 2 — 한글 자모 공백 제거:**

| 변경 전 | 변경 후 |
|---------|---------|
| `계 량 증 명 서` | `계량증명서` |
| `거 래 처: 곰욕환경폐기물` | `거래처: 곰욕환경폐기물` |
| `실 중 량: 5,010 kg` | `실중량: 5,010 kg` |
| `중 량:` | `중량:` |

**Step 3 — 노이즈 마킹:**

| Line | min conf | 판정 |
|------|----------|------|
| 4 `품종명랑 05:26:18 12,480 kg` | 0.19 | `isNoiseCandidate: true` (0.19 < 0.3) |
| 나머지 | >= 0.5 | `isNoiseCandidate: false` |

삭제 대상 없음 (모든 word가 conf > 0.1 이거나 단독 쓰레기 토큰이 아님).

**Step 4 — 라인 병합:**

| 동작 | 대상 |
|------|------|
| 조각 제거 | Line 5 `명:` — 1글자+콜론 패턴 매치 → 삭제 |
| 병합 | Line 6 `중량:` (중량 라벨) + Line 7 `05:36:01 7,470 kg` (중량 값) → `중량: 05:36:01 7,470 kg` |

**최종 결과 (11줄):**

```
[0]  "계량증명서"                          noise=false  conf=0.95
[1]  "계량일자: 2026-02-02 0016"           noise=false  conf=0.96
[2]  "차량번호: 8713"                      noise=false  conf=0.97
[3]  "거래처: 곰욕환경폐기물"                noise=false  conf=0.51
[4]  "품종명랑 05:26:18 12,480 kg"         noise=true   conf=0.19
[5]  "중량: 05:36:01 7,470 kg"             noise=false  conf=0.93  ← 병합 결과
[6]  "실중량: 5,010 kg"                    noise=false  conf=0.96
[7]  "* 위와 같이 계량하였음을 확인함."       noise=false  conf=0.89
[8]  "동우바이오(주)"                       noise=false  conf=0.98
[9]  "2026-02-02 05:37:55"                noise=false  conf=0.96
[10] "37.105317, 127.375673"              noise=false  conf=0.86
```

---

## 2. Extractor 출력

`List<ProcessedLine>` → `List<ClassifiedLine>`

| idx | 텍스트 | 분류 근거 | LineType |
|-----|--------|----------|----------|
| 0 | `계량증명서` | 콜론 없음, 패턴 해당 없음 | **ETC_LINE** |
| 1 | `계량일자: 2026-02-02 0016` | 콜론 있음, kg 없음 | **LABEL_VALUE_LINE** |
| 2 | `차량번호: 8713` | 콜론 있음, kg 없음 | **LABEL_VALUE_LINE** |
| 3 | `거래처: 곰욕환경폐기물` | 콜론 있음, kg 없음 | **LABEL_VALUE_LINE** |
| 4 | `품종명랑 05:26:18 12,480 kg` | 콜론 없음, kg+3~6자리 정수 | **WEIGHT_EVENT** |
| 5 | `중량: 05:36:01 7,470 kg` | 콜론 있음, kg 있음 | **WEIGHT_EVENT** |
| 6 | `실중량: 5,010 kg` | 콜론 있음, kg 있음 | **WEIGHT_EVENT** |
| 7 | `* 위와 같이 계량하였음을...` | 패턴 해당 없음 | **ETC_LINE** |
| 8 | `동우바이오(주)` | `(주)` 패턴 매치 | **ISSUER_LINE** |
| 9 | `2026-02-02 05:37:55` | YYYY-MM-DD HH:MM:SS | **TIMESTAMP_LINE** |
| 10 | `37.105317, 127.375673` | 소수점 좌표 | **GPS_LINE** |

**분포:** WEIGHT_EVENT=3, LABEL_VALUE_LINE=3, ETC_LINE=2, ISSUER_LINE=1, TIMESTAMP_LINE=1, GPS_LINE=1

---

## 3. FieldAssigner 출력

`List<ClassifiedLine>` → `ParsedDocument` (raw)

### 3-1. WEIGHT_EVENT → WeightCandidate 파싱

| # | 원본 텍스트 | weight | time | rawLabel | ocrConf |
|---|-----------|--------|------|----------|---------|
| A | `품종명랑 05:26:18 12,480 kg` | 12480 | 05:26:18 | `품종명랑` | 0.9799 |
| B | `중량: 05:36:01 7,470 kg` | 7470 | 05:36:01 | `중량` | 0.9258 |
| C | `실중량: 5,010 kg` | 5010 | null | `실중량` | 0.9685 |

### 3-2. 3중량 세트 선택

후보 3개 이하이므로 세트 선택 생략 → 전부 사용.

### 3-3. 역할 할당

**1순위 — 라벨 매칭:**

| 후보 | rawLabel | gross 스코어 | tare 스코어 | net 스코어 | 판정 |
|------|----------|-------------|------------|-----------|------|
| A | `품종명랑` | 0 | 0 | 0 | 매칭 실패 (OCR 깨짐) |
| B | `중량` | — | — | — | 약매칭(50)만 가능, 85 미달 |
| C | `실중량` | 0 | 0 | **85** (실중량 = net_weight 별칭) | **net_weight 확정** |

→ C가 `net_weight`로 확정 (inferred_by: LABEL)

**2순위 — 산술 관계:**

```
A(12480) - B(7470) = 5010 = C(5010) ✓
A가 최댓값 → A = gross_weight, B = tare_weight
```

→ A가 `gross_weight`, B가 `tare_weight`로 확정 (inferred_by: ARITHMETIC)

### 3-4. LABEL_VALUE_LINE 처리

| 라벨 | 값 | 별칭 매칭 | 필드 |
|------|-----|----------|------|
| `계량일자` | `2026-02-02 0016` | 계량일자 → measurement_date (85) | `measurement_date` = `2026-02-02`, `serial_number` = `0016` |
| `차량번호` | `8713` | 차량번호 → vehicle_number (85) | `vehicle_number` = `8713` |
| `거래처` | `곰욕환경폐기물` | 거래처 → customer (85) | `customer` = `곰욕환경폐기물` (WARNING, conf 0.51) |

### 3-5. 기타 라인 처리

| 라인 | LineType | 필드 |
|------|----------|------|
| `동우바이오(주)` | ISSUER_LINE | `issuer` |
| `2026-02-02 05:37:55` | TIMESTAMP_LINE | `issued_at` |
| `37.105317, 127.375673` | GPS_LINE | `gps_coordinates` |
| `계량증명서` | ETC_LINE | `document_type` (문서 유형 별칭 매칭) |

### 3-6. 할당 결과 요약

```
document_type: 계량증명서
fields: gross_weight, tare_weight, net_weight,
        measurement_date, serial_number, vehicle_number,
        customer, issuer, issued_at, gps_coordinates
unresolved: 없음
```

---

## 4. Normalizer 출력

`ParsedDocument` (raw) → `ParsedDocument` (normalized)

| 필드 | raw | normalized | 규칙 |
|------|-----|-----------|------|
| measurement_date | `2026-02-02` | `2026-02-02` | 이미 YYYY-MM-DD |
| issued_at | `2026-02-02 05:37:55` | `2026-02-02 05:37:55` | 이미 정규형 |
| gross_weight.time | `05:26:18` | `05:26:18` | 이미 HH:MM:SS |
| tare_weight.time | `05:36:01` | `05:36:01` | 이미 HH:MM:SS |
| gross_weight.unit | `kg` | `kg` | 이미 소문자 |
| 중량 값 | 12480, 7470, 5010 | 12480, 7470, 5010 | 이미 int |

이 샘플은 원본이 깨끗하여 정규화 변환이 거의 없다. `2026.02.02` → `2026-02-02`, `㎏` → `kg` 같은 변환은 다른 샘플에서 발생한다.

---

## 5. Validator 출력

`ParsedDocument` (normalized) → `ParsedDocument` (final)

**규칙 1 — 중량 산술:**
```
12480 - 7470 = 5010 = 5010 ✓ (delta=0, passed=true)
```

**규칙 2 — 중량 양수:** 12480 > 0, 7470 > 0, 5010 > 0 ✓

**규칙 3 — 총중량 최대값:** 12480 >= 7470, 12480 >= 5010 ✓

**규칙 4 — 날짜 유효성:** 2026-02-02 파싱 성공, 미래 날짜 아님 ✓

**규칙 5 — 필수 필드:**
```
문서 성립: measurement_date ✓, vehicle_number ✓
자동처리: gross_weight ✓, tare_weight ✓, net_weight ✓
권장:      customer ✓, issuer ✓
```

**최종 판정:**
```
is_consistent:  true  (산술 통과 + 필수 OK + 날짜 유효 + ERROR 없음)
is_actionable:  true  (consistent + 자동처리 OK + UNRESOLVED 없음)
field_warnings: null  (위반 없음)
```

---

## 최종 출력 (요약)

```json
{
  "document_type": "계량증명서",
  "source_file": "sample_01.json",
  "fields": {
    "gross_weight":    { "value": 12480, "unit": "kg", "time": "05:26:18", "inferred_by": "arithmetic", "status": "OK" },
    "tare_weight":     { "value": 7470,  "unit": "kg", "time": "05:36:01", "inferred_by": "arithmetic", "status": "OK" },
    "net_weight":      { "value": 5010,  "unit": "kg", "inferred_by": "label",      "status": "OK" },
    "measurement_date":{ "value": "2026-02-02", "status": "OK" },
    "serial_number":   { "value": "0016",       "status": "OK" },
    "vehicle_number":  { "value": "8713",       "status": "OK" },
    "customer":        { "value": "곰욕환경폐기물", "status": "WARNING", "severity": "LOW" },
    "issuer":          { "value": "동우바이오(주)",  "status": "OK" },
    "issued_at":       { "value": "2026-02-02 05:37:55", "status": "OK" },
    "gps_coordinates": { "value": "37.105317, 127.375673", "status": "OK" }
  },
  "validation": {
    "is_consistent": true,
    "is_actionable": true,
    "weight_arithmetic": { "passed": true, "delta": 0 }
  }
}
```
