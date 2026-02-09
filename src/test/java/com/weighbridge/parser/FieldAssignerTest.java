package com.weighbridge.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weighbridge.parser.model.*;
import com.weighbridge.parser.pipeline.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldAssignerTest {

    private Preprocessor preprocessor;
    private Extractor extractor;
    private FieldAssigner fieldAssigner;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        preprocessor = new Preprocessor();
        extractor = new Extractor();
        fieldAssigner = new FieldAssigner();
        objectMapper = new ObjectMapper();
    }

    private ParsedDocument parseSample(String filename) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/" + filename);
        assertThat(is).as("Fixture %s", filename).isNotNull();
        OcrInput input = objectMapper.readValue(is, OcrInput.class);
        List<ProcessedLine> processed = preprocessor.process(input);
        List<ClassifiedLine> classified = extractor.classifyLines(processed);
        return fieldAssigner.assign(classified, filename);
    }

    private ParsedDocument parseText(String... texts) {
        return parseTextWithConfidence(0.95, texts);
    }

    private ParsedDocument parseTextWithConfidence(double confidence, String... texts) {
        List<OcrInput.Line> lines = new java.util.ArrayList<>();
        for (String text : texts) {
            lines.add(new OcrInput.Line(text, List.of(
                    new OcrInput.Word(text, confidence)
            )));
        }
        OcrInput input = new OcrInput(null, lines);
        List<ProcessedLine> processed = preprocessor.process(input);
        List<ClassifiedLine> classified = extractor.classifyLines(processed);
        return fieldAssigner.assign(classified, "test");
    }

    // ── weight_event 값 분리 ──

    @Nested
    @DisplayName("Step 2-1: weight_event 값 분리")
    class WeightEventParsingTest {

        @Test
        @DisplayName("'품종명랑 05:26:18 12,480 kg' → weight=12480, time=05:26:18")
        void parseWeightWithTime() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.value()).isEqualTo(12480);
            assertThat(gross.time()).isEqualTo("05:26:18");
        }

        @Test
        @DisplayName("'실중량: 5,010 kg' → weight=5010")
        void parseNetWeight() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField net = (WeightField) doc.fields().get("net_weight");
            assertThat(net.value()).isEqualTo(5010);
        }

        @Test
        @DisplayName("3개 weight_event 모두 파싱 — 구체적 값 검증")
        void parseAllThree() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");

            assertThat(gross.value()).isEqualTo(12480);
            assertThat(tare.value()).isEqualTo(7470);
            assertThat(net.value()).isEqualTo(5010);
        }
    }

    // ── 산술 관계 검증 ──

    @Nested
    @DisplayName("Step 2-3: 역할 할당")
    class RoleAssignmentTest {

        @Test
        @DisplayName("sample_01: gross=12480, tare=7470, net=5010 (12480-7470=5010)")
        void arithmeticTriple() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");

            assertThat(gross.value()).isEqualTo(12480);
            assertThat(tare.value()).isEqualTo(7470);
            assertThat(net.value()).isEqualTo(5010);
            assertThat(gross.value() - tare.value()).isEqualTo(net.value());
        }

        @Test
        @DisplayName("'실중량:' 라벨 → net_weight inferred_by는 LABEL 또는 ARITHMETIC")
        void labelInferredBy() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField net = (WeightField) doc.fields().get("net_weight");
            assertThat(net.inferredBy()).isNotNull();
            assertThat(net.inferredBy()).isIn(InferredBy.LABEL, InferredBy.ARITHMETIC);
            assertThat(net.assignmentConfidence()).isGreaterThanOrEqualTo(0.9);
        }

        @Test
        @DisplayName("라벨 없는 중량 3개 + 산술 성립 → arithmetic 할당")
        void arithmeticWithoutLabels() {
            ParsedDocument doc = parseText(
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 3000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");

            assertThat(gross.value()).isEqualTo(10000);
            assertThat(tare.value()).isEqualTo(7000);
            assertThat(net.value()).isEqualTo(3000);
        }

        @Test
        @DisplayName("산술 불일치 + 라벨 없음 → UNRESOLVED")
        void unresolvedNoArithmetic() {
            ParsedDocument doc = parseText(
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 4000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");

            boolean hasUnresolved = gross.status() == FieldStatus.UNRESOLVED
                    || tare.status() == FieldStatus.UNRESOLVED
                    || net.status() == FieldStatus.UNRESOLVED;
            assertThat(hasUnresolved).isTrue();
        }
    }

    // ── inferred_by 기록 ──

    @Nested
    @DisplayName("Step 2-5: inferred_by")
    class InferredByTest {

        @Test
        @DisplayName("한글 라벨 정확 매칭 → LABEL 할당, confidence=1.0")
        void koreanLabelExactMatch() {
            ParsedDocument doc = parseText(
                    "총중량: 10000 kg",
                    "공차중량: 7000 kg",
                    "실중량: 3000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.inferredBy()).isEqualTo(InferredBy.LABEL);
            assertThat(gross.assignmentConfidence()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("라벨 없는 3개 + 산술 성립 → ARITHMETIC, confidence=0.9")
        void arithmeticConfidence() {
            ParsedDocument doc = parseText(
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 3000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.inferredBy()).isEqualTo(InferredBy.ARITHMETIC);
            assertThat(gross.assignmentConfidence()).isEqualTo(0.9);
        }
    }

    // ── label_value_line 처리 ──

    @Nested
    @DisplayName("Step 2-6: label_value_line 처리")
    class LabelValueLineTest {

        @Test
        @DisplayName("sample_01: measurement_date = '2026-02-02'")
        void measurementDate() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField dateField = (BaseField) doc.fields().get("measurement_date");
            assertThat(dateField).isNotNull();
            assertThat(dateField.value()).contains("2026-02-02");
        }

        @Test
        @DisplayName("sample_01: vehicle_number = '8713'")
        void vehicleNumber() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField field = (BaseField) doc.fields().get("vehicle_number");
            assertThat(field).isNotNull();
            assertThat(field.value()).isEqualTo("8713");
        }

        @Test
        @DisplayName("sample_01: customer = '곰욕환경폐기물'")
        void customer() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField field = (BaseField) doc.fields().get("customer");
            assertThat(field).isNotNull();
            assertThat(field.value()).isEqualTo("곰욕환경폐기물");
        }

        @Test
        @DisplayName("serial_number 추출: '2026-02-02 0016' → serial='0016'")
        void serialNumber() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField serial = (BaseField) doc.fields().get("serial_number");
            assertThat(serial).isNotNull();
            assertThat(serial.value()).isEqualTo("0016");
        }

        @Test
        @DisplayName("measurement_date에서 serial_number 분리 후 날짜만 남김")
        void dateSeparatedFromSerial() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField dateField = (BaseField) doc.fields().get("measurement_date");
            assertThat(dateField.value()).isEqualTo("2026-02-02");
        }
    }

    // ── 기타 라인 처리 ──

    @Nested
    @DisplayName("Step 2-7: 기타 라인 처리")
    class OtherLinesTest {

        @Test
        @DisplayName("sample_01: document_type = '계량증명서'")
        void documentType() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            assertThat(doc.documentType()).isEqualTo("계량증명서");
        }

        @Test
        @DisplayName("sample_01: issuer = '동우바이오(주)'")
        void issuer() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField issuer = (BaseField) doc.fields().get("issuer");
            assertThat(issuer).isNotNull();
            assertThat(issuer.value()).isEqualTo("동우바이오(주)");
        }

        @Test
        @DisplayName("sample_01: issued_at 존재")
        void issuedAt() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField issuedAt = (BaseField) doc.fields().get("issued_at");
            assertThat(issuedAt).isNotNull();
            assertThat(issuedAt.value()).contains("2026-02-02");
            assertThat(issuedAt.value()).contains("05:37:55");
        }

        @Test
        @DisplayName("sample_01: gps_coordinates 존재")
        void gps() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            BaseField gps = (BaseField) doc.fields().get("gps_coordinates");
            assertThat(gps).isNotNull();
            assertThat(gps.value()).contains("37.105317");
        }
    }

    // ── confidence 집계 ──

    @Nested
    @DisplayName("Step 2-8: confidence 집계")
    class ConfidenceTest {

        @Test
        @DisplayName("ocr_confidence는 0~1 범위")
        void ocrConfidenceRange() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.ocrConfidence()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("assignment_confidence는 0~1 범위")
        void assignmentConfidenceRange() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.assignmentConfidence()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("단위는 'kg'로 통일")
        void unitNormalized() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.unit()).isEqualTo("kg");
        }
    }

    // ── 3중량 세트 선택 ──

    @Nested
    @DisplayName("Step 2-2: 3중량 세트 선택")
    class TripleSelectionTest {

        @Test
        @DisplayName("5개 weight_event → 산술 트리플 선택 + extra_weights 2개")
        void fiveWeightEvents() {
            ParsedDocument doc = parseText(
                    "총중량: 05:00:00 20000 kg",
                    "공차중량: 06:00:00 12000 kg",
                    "실중량: 07:00:00 8000 kg",
                    "08:00:00 5000 kg",
                    "09:00:00 3000 kg"
            );

            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");

            assertThat(gross.value()).isEqualTo(20000);
            assertThat(tare.value()).isEqualTo(12000);
            assertThat(net.value()).isEqualTo(8000);
            assertThat(doc.extraWeights()).hasSize(2);
        }

        @Test
        @DisplayName("정확히 3개면 세트 선택 skip")
        void exactlyThree() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            assertThat(doc.extraWeights()).isEmpty();
        }
    }

    // ── UNRESOLVED 처리 ──

    @Nested
    @DisplayName("Step 2-4: UNRESOLVED 처리")
    class UnresolvedTest {

        @Test
        @DisplayName("weight_roles_unresolved 플래그")
        void unresolvedFlag() {
            ParsedDocument doc = parseText(
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 4000 kg"
            );
            assertThat(doc.validation().weightRolesUnresolved()).isTrue();
        }

        @Test
        @DisplayName("UNRESOLVED 시 resolution_hint 생성")
        void resolutionHint() {
            ParsedDocument doc = parseText(
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 4000 kg"
            );
            assertThat(doc.validation().weightRolesUnresolved()).isTrue();
            assertThat(doc.resolutionHint()).isNotNull();
            assertThat(doc.resolutionHint().description()).contains("수동 확인");
        }
    }

    // ── 엣지 케이스: 0/1/2 중량 ──

    @Nested
    @DisplayName("엣지 케이스: 0/1/2 중량 입력")
    class WeightCountEdgeCaseTest {

        @Test
        @DisplayName("중량 0개 → 모두 MISSING")
        void zeroWeights() {
            ParsedDocument doc = parseText(
                    "계량증명서",
                    "계량일자: 2026-01-15",
                    "차량번호: 1234"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");

            assertThat(gross.status()).isEqualTo(FieldStatus.MISSING);
            assertThat(tare.status()).isEqualTo(FieldStatus.MISSING);
            assertThat(net.status()).isEqualTo(FieldStatus.MISSING);
        }

        @Test
        @DisplayName("중량 1개 + 라벨 정확 매칭 → gross_weight LABEL 할당")
        void singleWeight() {
            ParsedDocument doc = parseText(
                    "총중량: 10000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.value()).isEqualTo(10000);
            assertThat(gross.inferredBy()).isEqualTo(InferredBy.LABEL);
        }

        @Test
        @DisplayName("중량 2개 + 라벨 정확 매칭 → gross/tare LABEL 할당")
        void twoWeights() {
            ParsedDocument doc = parseText(
                    "총중량: 10000 kg",
                    "공차중량: 7000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            assertThat(gross.value()).isEqualTo(10000);
            assertThat(gross.inferredBy()).isEqualTo(InferredBy.LABEL);
            assertThat(tare.value()).isEqualTo(7000);
            assertThat(tare.inferredBy()).isEqualTo(InferredBy.LABEL);
        }
    }

    // ── OCR 깨진 단위 중량 할당 ──

    @Nested
    @DisplayName("OCR 깨진 단위 처리")
    class BrokenUnitAssignmentTest {

        @ParameterizedTest(name = "단위 ''{0}'' → weight_event로 파싱")
        @ValueSource(strings = {"kg", "KG", "㎏", "Kg", "k9", "kq"})
        void brokenKgUnits(String unit) {
            ParsedDocument doc = parseText(
                    "총중량: 10000 " + unit,
                    "공차중량: 7000 " + unit,
                    "실중량: 3000 " + unit
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.value()).isEqualTo(10000);
            assertThat(gross.unit()).isEqualTo("kg");
        }
    }

    // ── OCR confidence WARNING ──

    @Nested
    @DisplayName("OCR confidence WARNING")
    class OcrConfidenceTest {

        @Test
        @DisplayName("ARITHMETIC + 정상 OCR → OK")
        void arithmeticNormalOcr() {
            ParsedDocument doc = parseTextWithConfidence(0.95,
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 3000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.inferredBy()).isEqualTo(InferredBy.ARITHMETIC);
            assertThat(gross.status()).isEqualTo(FieldStatus.OK);
        }

        @Test
        @DisplayName("ARITHMETIC + 낮은 OCR(0.5) → WARNING")
        void arithmeticLowOcr() {
            ParsedDocument doc = parseTextWithConfidence(0.5,
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 3000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.inferredBy()).isEqualTo(InferredBy.ARITHMETIC);
            assertThat(gross.status()).isEqualTo(FieldStatus.WARNING);
            assertThat(gross.severity()).isEqualTo(Severity.LOW);
        }
    }

    // ── raw_label 회귀 테스트 ──

    @Nested
    @DisplayName("raw_label 회귀 테스트")
    class RawLabelRegressionTest {

        @Test
        @DisplayName("시간 콜론이 라벨에 포함되지 않아야 함")
        void timeColonNotInLabel() throws Exception {
            ParsedDocument doc = parseSample("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            if (gross.rawLabel() != null) {
                assertThat(gross.rawLabel()).doesNotContain(":");
            }
        }
    }
}
