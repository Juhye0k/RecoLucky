# 시퀀스 다이어그램

## 메인 파이프라인

```mermaid
sequenceDiagram
    participant App as Application
    participant Pre as Preprocessor
    participant Ext as Extractor
    participant FA as FieldAssigner
    participant Nor as Normalizer
    participant Val as Validator

    App->>App: OCR JSON 파일 읽기 (Jackson)
    App->>Pre: process(OcrInput)

    Note over Pre: Step 1. 라인 분리<br/>Step 2. 한글 자모 공백 제거<br/>Step 3. 노이즈 마킹 (삭제/플래그)<br/>Step 4. 라인 병합
    Pre-->>App: List&lt;ProcessedLine&gt;

    App->>Ext: classifyLines(processedLines)
    Note over Ext: Value-First 라인 분류<br/>→ 약후보 승격/강등
    Ext-->>App: List&lt;ClassifiedLine&gt;

    App->>FA: assign(classifiedLines, sourceFile)
    Note over FA: 중량 후보 파싱<br/>→ 3중량 세트 선택<br/>→ 역할 할당 (라벨→산술→UNRESOLVED)<br/>→ 라벨:값 필드 할당<br/>→ 기타 라인 처리
    FA-->>App: ParsedDocument (raw)

    App->>Nor: normalize(doc)
    Note over Nor: 날짜→YYYY-MM-DD<br/>시간→HH:MM:SS<br/>중량→int, 단위→kg
    Nor-->>App: ParsedDocument (normalized)

    App->>Val: validate(doc)
    Note over Val: 산술 검증<br/>양수/최대값 검증<br/>날짜 유효성<br/>필수 필드 확인<br/>→ is_consistent, is_actionable
    Val-->>App: ParsedDocument (final)

    App->>App: JSON 출력 (stdout 또는 파일)
```

## Extractor 라인 분류 상세

```mermaid
flowchart TD
    Start([라인 입력]) --> HasColon{콜론 있음?}

    HasColon -- Yes --> ColonKg{kg 단위?}
    ColonKg -- "Yes (guard 통과)" --> WE[WEIGHT_EVENT]
    ColonKg -- No --> LV[LABEL_VALUE_LINE]

    HasColon -- No --> IsTimestamp{타임스탬프?}
    IsTimestamp -- Yes --> TS[TIMESTAMP_LINE]
    IsTimestamp -- No --> IsGPS{GPS 좌표?}
    IsGPS -- Yes --> GPS[GPS_LINE]
    IsGPS -- No --> IsKgNoColon{kg + 3~6자리?}
    IsKgNoColon -- "Yes (guard 통과)" --> WE2[WEIGHT_EVENT]
    IsKgNoColon -- No --> IsTimeInt{time + 정수?}
    IsTimeInt -- Yes --> CAND["약후보 (candidate)"]
    IsTimeInt -- No --> IsDate{날짜 패턴?}
    IsDate -- Yes --> DT[DATE_LINE]
    IsDate -- No --> IsIssuer{"(주)/Co. 패턴?"}
    IsIssuer -- Yes --> ISS[ISSUER_LINE]
    IsIssuer -- No --> ETC[ETC_LINE]

    CAND --> Promote{문서 내 kg >= 1<br/>AND +-2라인 라벨 힌트?}
    Promote -- Yes --> WE3[WEIGHT_EVENT 승격]
    Promote -- No --> ETC2[ETC_LINE 강등]
```

## FieldAssigner 중량 역할 할당 상세

```mermaid
flowchart TD
    Start([WeightCandidate 목록]) --> Count{후보 >= 4개?}

    Count -- Yes --> Triple[3중량 세트 선택]
    Triple --> Arith{A - B = C<br/>산술 성립?}
    Arith -- Yes --> ArithTriple["산술 트리플 채택<br/>나머지 → extra_weights"]
    Arith -- No --> LabelTop["라벨 스코어 상위 3개<br/>나머지 → extra_weights"]

    Count -- "No (1~3개)" --> Selected[전부 사용]

    ArithTriple --> RoleAssign
    LabelTop --> RoleAssign
    Selected --> RoleAssign

    RoleAssign[역할 할당 시작] --> LabelMatch["1순위: 라벨 매칭<br/>(스코어 >= 85 → 확정)"]
    LabelMatch --> ArithMatch["2순위: 산술 관계<br/>(A=gross, B=tare, C=net)"]
    ArithMatch --> Remaining{미할당 잔여?}
    Remaining -- No --> Done([할당 완료])
    Remaining -- Yes --> UNRESOLVED["UNRESOLVED<br/>+ resolution_hint 생성"]
```
