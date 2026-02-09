package com.weighbridge.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weighbridge.parser.model.OcrInput;
import com.weighbridge.parser.model.ProcessedLine;
import com.weighbridge.parser.pipeline.ClassifiedLine;
import com.weighbridge.parser.pipeline.Extractor;
import com.weighbridge.parser.pipeline.LineType;
import com.weighbridge.parser.pipeline.Preprocessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorTest {

    private Preprocessor preprocessor;
    private Extractor extractor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        preprocessor = new Preprocessor();
        extractor = new Extractor();
        objectMapper = new ObjectMapper();
    }

    private List<ClassifiedLine> classifySample(String filename) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/" + filename);
        assertThat(is).as("Fixture %s", filename).isNotNull();
        OcrInput input = objectMapper.readValue(is, OcrInput.class);
        List<ProcessedLine> processed = preprocessor.process(input);
        return extractor.classifyLines(processed);
    }

    private List<ClassifiedLine> classifyText(String... texts) {
        List<OcrInput.Line> lines = new java.util.ArrayList<>();
        for (String text : texts) {
            lines.add(new OcrInput.Line(text, List.of(
                    new OcrInput.Word(text, 0.95)
            )));
        }
        OcrInput input = new OcrInput(null, lines);
        List<ProcessedLine> processed = preprocessor.process(input);
        return extractor.classifyLines(processed);
    }

    private ClassifiedLine findLineContaining(List<ClassifiedLine> lines, String substring) {
        return lines.stream()
                .filter(cl -> cl.text().contains(substring))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No line containing: " + substring));
    }

    // ── label_delimiter_colon 판정 ──

    @Nested
    @DisplayName("label_delimiter_colon 판정")
    class LabelColonTest {

        @Test
        @DisplayName("일반 라벨 콜론 → true")
        void normalCases() {
            assertThat(Extractor.hasLabelDelimiterColon("계량일자: 2026-02-02 0016")).isTrue();
            assertThat(Extractor.hasLabelDelimiterColon("계량일자:2026-02-02")).isTrue();
            assertThat(Extractor.hasLabelDelimiterColon("실중량： 5,010 kg")).isTrue();
        }

        @Test
        @DisplayName("시간/타임스탬프 콜론 → false")
        void timeExcluded() {
            assertThat(Extractor.hasLabelDelimiterColon("02:07 13 460 kg")).isFalse();
            assertThat(Extractor.hasLabelDelimiterColon("2026-02-02 05:37:55")).isFalse();
        }
    }

    // ── sample_01 라인 분류 ──

    @Nested
    @DisplayName("sample_01 라인 분류")
    class Sample01Test {

        @Test
        @DisplayName("주요 라인 분류")
        void mainClassifications() throws Exception {
            List<ClassifiedLine> lines = classifySample("sample_01.json");

            assertThat(findLineContaining(lines, "계량증명서").type())
                    .isEqualTo(LineType.ETC_LINE);
            assertThat(findLineContaining(lines, "계량일자").type())
                    .isEqualTo(LineType.LABEL_VALUE_LINE);
            assertThat(findLineContaining(lines, "차량번호").type())
                    .isEqualTo(LineType.LABEL_VALUE_LINE);
            assertThat(findLineContaining(lines, "거래처").type())
                    .isEqualTo(LineType.LABEL_VALUE_LINE);
            assertThat(findLineContaining(lines, "동우바이오").type())
                    .isEqualTo(LineType.ISSUER_LINE);
            assertThat(findLineContaining(lines, "37.105317").type())
                    .isEqualTo(LineType.GPS_LINE);
            assertThat(findLineContaining(lines, "05:37:55").type())
                    .isEqualTo(LineType.TIMESTAMP_LINE);
        }

        @Test
        @DisplayName("weight_event 3개 확인")
        void weightEvents() throws Exception {
            List<ClassifiedLine> lines = classifySample("sample_01.json");

            assertThat(findLineContaining(lines, "12,480 kg").type())
                    .isEqualTo(LineType.WEIGHT_EVENT);
            assertThat(findLineContaining(lines, "7,470 kg").type())
                    .isEqualTo(LineType.WEIGHT_EVENT);
            assertThat(findLineContaining(lines, "5,010 kg").type())
                    .isEqualTo(LineType.WEIGHT_EVENT);

            long weightCount = lines.stream()
                    .filter(cl -> cl.type() == LineType.WEIGHT_EVENT)
                    .count();
            assertThat(weightCount).isEqualTo(3);
        }
    }

    // ── issuer 과탐 방지 ──

    @Nested
    @DisplayName("issuer 과탐 방지")
    class IssuerTest {

        @Test
        @DisplayName("negative lexicon / 주소 / 전화번호 → issuer 아님")
        void negativePatterns() {
            assertThat(classifyText("C&S TEL").get(0).type())
                    .isNotEqualTo(LineType.ISSUER_LINE);
            assertThat(classifyText("(주)한진 경기도 용인시 처인구").get(0).type())
                    .isNotEqualTo(LineType.ISSUER_LINE);
            assertThat(classifyText("(주)한진 031-354-7778").get(0).type())
                    .isNotEqualTo(LineType.ISSUER_LINE);
        }

        @Test
        @DisplayName("유효한 issuer 패턴")
        void validIssuers() {
            assertThat(classifyText("동우바이오(주)").get(0).type())
                    .isEqualTo(LineType.ISSUER_LINE);
            assertThat(classifyText("장원C&S").get(0).type())
                    .isEqualTo(LineType.ISSUER_LINE);
        }
    }

    // ── 기타 분류 ──

    @Nested
    @DisplayName("기타 분류")
    class MiscTest {

        @Test
        @DisplayName("단독 날짜 → date_line")
        void standaloneDate() {
            assertThat(classifyText("2026-02-02").get(0).type())
                    .isEqualTo(LineType.DATE_LINE);
        }

        @Test
        @DisplayName("확인 텍스트 → etc_line")
        void confirmationText() {
            assertThat(classifyText("* 위와 같이 계량하였음을 확인함.").get(0).type())
                    .isEqualTo(LineType.ETC_LINE);
        }
    }
}
