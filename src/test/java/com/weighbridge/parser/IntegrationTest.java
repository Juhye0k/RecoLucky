package com.weighbridge.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weighbridge.parser.model.*;
import com.weighbridge.parser.output.JsonWriter;
import com.weighbridge.parser.pipeline.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private ParsedDocument fullPipeline(String filename) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/" + filename);
        assertThat(is).as("Fixture %s", filename).isNotNull();
        OcrInput input = objectMapper.readValue(is, OcrInput.class);

        Preprocessor preprocessor = new Preprocessor();
        Extractor extractor = new Extractor();
        FieldAssigner fieldAssigner = new FieldAssigner();
        Normalizer normalizer = new Normalizer();
        Validator validator = new Validator();

        List<ProcessedLine> processed = preprocessor.process(input);
        List<ClassifiedLine> classified = extractor.classifyLines(processed);
        ParsedDocument doc = fieldAssigner.assign(classified, filename);
        doc = normalizer.normalize(doc);
        doc = validator.validate(doc);
        return doc;
    }

    private ParsedDocument pipelineFromText(String... texts) {
        List<OcrInput.Line> lines = new java.util.ArrayList<>();
        for (String text : texts) {
            lines.add(new OcrInput.Line(text, List.of(
                    new OcrInput.Word(text, 0.95)
            )));
        }
        OcrInput input = new OcrInput(null, lines);

        Preprocessor preprocessor = new Preprocessor();
        Extractor extractor = new Extractor();
        FieldAssigner fieldAssigner = new FieldAssigner();
        Normalizer normalizer = new Normalizer();
        Validator validator = new Validator();

        List<ProcessedLine> processed = preprocessor.process(input);
        List<ClassifiedLine> classified = extractor.classifyLines(processed);
        ParsedDocument doc = fieldAssigner.assign(classified, "test");
        doc = normalizer.normalize(doc);
        doc = validator.validate(doc);
        return doc;
    }

    // ── sample_01 ──

    @Nested
    @DisplayName("sample_01 전체 파이프라인")
    class Sample01Test {

        @Test
        @DisplayName("필드 추출 + 산술 검증")
        void fieldsAndArithmetic() throws Exception {
            ParsedDocument doc = fullPipeline("sample_01.json");

            assertThat(doc.documentType()).isEqualTo("계량증명서");

            BaseField date = (BaseField) doc.fields().get("measurement_date");
            assertThat(date.value()).isEqualTo("2026-02-02");

            BaseField serial = (BaseField) doc.fields().get("serial_number");
            assertThat(serial).isNotNull();
            assertThat(serial.value()).isEqualTo("0016");

            BaseField vehicle = (BaseField) doc.fields().get("vehicle_number");
            assertThat(vehicle.value()).isEqualTo("8713");

            BaseField customer = (BaseField) doc.fields().get("customer");
            assertThat(customer.value()).isEqualTo("곰욕환경폐기물");

            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.value()).isEqualTo(12480);
            assertThat(gross.time()).isEqualTo("05:26:18");
            assertThat(gross.unit()).isEqualTo("kg");

            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            assertThat(tare.value()).isEqualTo(7470);

            WeightField net = (WeightField) doc.fields().get("net_weight");
            assertThat(net.value()).isEqualTo(5010);

            assertThat(doc.validation().weightArithmetic().get("passed")).isEqualTo(true);
            assertThat(doc.validation().isConsistent()).isTrue();
            assertThat(doc.validation().isActionable()).isTrue();
            assertThat(doc.extraWeights()).isEmpty();
        }

        @Test
        @DisplayName("issuer + timestamp + GPS")
        void issuerTimestampGps() throws Exception {
            ParsedDocument doc = fullPipeline("sample_01.json");

            BaseField issuer = (BaseField) doc.fields().get("issuer");
            assertThat(issuer.value()).isEqualTo("동우바이오(주)");

            BaseField issuedAt = (BaseField) doc.fields().get("issued_at");
            assertThat(issuedAt).isNotNull();
            assertThat(issuedAt.value()).contains("2026-02-02");

            BaseField gps = (BaseField) doc.fields().get("gps_coordinates");
            assertThat(gps).isNotNull();
            assertThat(gps.value()).contains("37.105317");
        }

        @Test
        @DisplayName("JSON 출력 가능")
        void jsonOutput() throws Exception {
            ParsedDocument doc = fullPipeline("sample_01.json");
            JsonWriter writer = new JsonWriter();
            String json = writer.toJson(doc);
            assertThat(json).contains("\"document_type\"");
            assertThat(json).contains("12480");
        }
    }

    // ── sample_02 ──

    @Nested
    @DisplayName("sample_02 전체 파이프라인")
    class Sample02Test {

        @Test
        @DisplayName("필드 추출 + 산술 검증")
        void fieldsAndArithmetic() throws Exception {
            ParsedDocument doc = fullPipeline("sample_02.json");

            BaseField date = (BaseField) doc.fields().get("measurement_date");
            assertThat(date).isNotNull();
            assertThat(date.value()).isEqualTo("2026-02-02");

            BaseField vehicle = (BaseField) doc.fields().get("vehicle_number");
            assertThat(vehicle).isNotNull();
            assertThat(vehicle.value()).isEqualTo("80구8713");

            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");
            assertThat(gross.value()).isEqualTo(13460);
            assertThat(tare.value()).isEqualTo(7560);
            assertThat(net.value()).isEqualTo(5900);

            assertThat(doc.validation().isConsistent()).isTrue();

            BaseField issuer = (BaseField) doc.fields().get("issuer");
            assertThat(issuer).isNotNull();
            assertThat(issuer.value()).isEqualTo("장원C&S");
        }
    }

    // ── sample_03 ──

    @Nested
    @DisplayName("sample_03 전체 파이프라인")
    class Sample03Test {

        @Test
        @DisplayName("필드 추출 + 산술 검증")
        void fieldsAndArithmetic() throws Exception {
            ParsedDocument doc = fullPipeline("sample_03.json");

            BaseField date = (BaseField) doc.fields().get("measurement_date");
            assertThat(date).isNotNull();
            assertThat(date.value()).isEqualTo("2026-02-01");

            BaseField vehicle = (BaseField) doc.fields().get("vehicle_number");
            assertThat(vehicle).isNotNull();
            assertThat(vehicle.value()).contains("5405");

            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");
            assertThat(gross.value()).isEqualTo(14080);
            assertThat(tare.value()).isEqualTo(13950);
            assertThat(net.value()).isEqualTo(130);

            assertThat(doc.validation().isConsistent()).isTrue();
        }

        @Test
        @DisplayName("TEL 라인 미추출")
        void telNotExtracted() throws Exception {
            ParsedDocument doc = fullPipeline("sample_03.json");
            for (Object value : doc.fields().values()) {
                if (value instanceof BaseField bf && bf.value() != null) {
                    assertThat(bf.value()).doesNotContain("031-354-7778");
                }
            }
        }
    }

    // ── sample_04 ──

    @Nested
    @DisplayName("sample_04 전체 파이프라인")
    class Sample04Test {

        @Test
        @DisplayName("필드 추출 + 산술 검증")
        void fieldsAndArithmetic() throws Exception {
            ParsedDocument doc = fullPipeline("sample_04.json");

            BaseField date = (BaseField) doc.fields().get("measurement_date");
            assertThat(date).isNotNull();
            assertThat(date.value()).isEqualTo("2025-12-01");

            BaseField vehicle = (BaseField) doc.fields().get("vehicle_number");
            assertThat(vehicle).isNotNull();
            assertThat(vehicle.value()).isEqualTo("0580");

            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            WeightField tare = (WeightField) doc.fields().get("tare_weight");
            WeightField net = (WeightField) doc.fields().get("net_weight");
            assertThat(gross.value()).isEqualTo(14230);
            assertThat(tare.value()).isEqualTo(12910);
            assertThat(net.value()).isEqualTo(1320);

            assertThat(doc.validation().isConsistent()).isTrue();
        }
    }

    // ── 엣지 케이스 ──

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCaseTest {

        @Test
        @DisplayName("빈 입력 → MISSING 필드")
        void emptyInput() {
            OcrInput input = new OcrInput(null, List.of());
            Preprocessor preprocessor = new Preprocessor();
            Extractor extractor = new Extractor();
            FieldAssigner fieldAssigner = new FieldAssigner();
            Normalizer normalizer = new Normalizer();
            Validator validator = new Validator();

            List<ProcessedLine> processed = preprocessor.process(input);
            List<ClassifiedLine> classified = extractor.classifyLines(processed);
            ParsedDocument doc = fieldAssigner.assign(classified, "empty");
            doc = normalizer.normalize(doc);
            doc = validator.validate(doc);

            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.status()).isEqualTo(FieldStatus.MISSING);
        }

        @Test
        @DisplayName("필수 필드 전부 누락 → is_consistent=false")
        void allRequiredMissing() {
            ParsedDocument doc = pipelineFromText("* 위와 같이 계량하였음을 확인함.");
            assertThat(doc.validation().isConsistent()).isFalse();
        }

        @Test
        @DisplayName("라벨+kg 혼재 라인 처리")
        void labelKgMixed() {
            ParsedDocument doc = pipelineFromText(
                    "계량증명서",
                    "계량일자: 2026-01-15",
                    "차량번호: 1234",
                    "총중량: 05:00:00 10000 kg",
                    "공차중량: 06:00:00 7000 kg",
                    "실중량: 3000 kg"
            );
            WeightField net = (WeightField) doc.fields().get("net_weight");
            assertThat(net.value()).isEqualTo(3000);
        }
    }

    // ── 오분류 방지 ──

    @Nested
    @DisplayName("오분류 방지")
    class MisclassificationTest {

        @Test
        @DisplayName("'품종명랑 + kg' → weight_event (product_name 아님)")
        void brokenLabelKg() throws Exception {
            ParsedDocument doc = fullPipeline("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.value()).isEqualTo(12480);

            Object productName = doc.fields().get("product_name");
            if (productName instanceof BaseField bf) {
                assertThat(bf.value()).doesNotContain("12480");
            }
        }

        @Test
        @DisplayName("guard rule: '단가: 12,000원/kg' → weight_event 아님")
        void guardRule() {
            ParsedDocument doc = pipelineFromText(
                    "계량증명서",
                    "계량일자: 2026-01-15",
                    "차량번호: 1234",
                    "단가: 12,000원/kg",
                    "총중량: 10000 kg",
                    "공차중량: 7000 kg",
                    "실중량: 3000 kg"
            );
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.value()).isEqualTo(10000);
        }
    }

    // ── UNRESOLVED / 4개 이상 중량 ──

    @Nested
    @DisplayName("UNRESOLVED / 다중 중량")
    class UnresolvedTest {

        @Test
        @DisplayName("라벨 없는 중량 3개 + 산술 불일치 → UNRESOLVED")
        void unresolvedNoLabels() {
            ParsedDocument doc = pipelineFromText(
                    "계량증명서",
                    "계량일자: 2026-01-15",
                    "차량번호: 1234",
                    "05:00:00 10000 kg",
                    "06:00:00 7000 kg",
                    "07:00:00 4000 kg"
            );
            assertThat(doc.validation().weightRolesUnresolved()).isTrue();
        }

        @Test
        @DisplayName("weight_event 5개 → 산술 트리플 선택 + extra_weights")
        void fiveWeightEvents() {
            ParsedDocument doc = pipelineFromText(
                    "계량증명서",
                    "계량일자: 2026-01-15",
                    "차량번호: 1234",
                    "총중량: 05:00:00 20000 kg",
                    "공차중량: 06:00:00 12000 kg",
                    "실중량: 07:00:00 8000 kg",
                    "08:00:00 5000 kg",
                    "09:00:00 3000 kg"
            );
            assertThat(doc.extraWeights()).hasSize(2);
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.value()).isEqualTo(20000);
        }
    }

    // ── confidence / issuer ──

    @Nested
    @DisplayName("confidence / issuer")
    class ConfidenceIssuerTest {

        @Test
        @DisplayName("ocr_confidence / assignment_confidence 독립")
        void independentConfidence() throws Exception {
            ParsedDocument doc = fullPipeline("sample_01.json");
            WeightField gross = (WeightField) doc.fields().get("gross_weight");
            assertThat(gross.ocrConfidence()).isBetween(0.0, 1.0);
            assertThat(gross.assignmentConfidence()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("issuer 과탐 방지 (negative lexicon)")
        void issuerNegativeLexicon() {
            ParsedDocument doc = pipelineFromText(
                    "계량증명서",
                    "계량일자: 2026-01-15",
                    "차량번호: 1234",
                    "총중량: 10000 kg",
                    "공차중량: 7000 kg",
                    "실중량: 3000 kg",
                    "C&S 관리팀"
            );
            BaseField issuer = (BaseField) doc.fields().get("issuer");
            if (issuer != null) {
                assertThat(issuer.value()).doesNotContain("관리팀");
            }
        }
    }
}
