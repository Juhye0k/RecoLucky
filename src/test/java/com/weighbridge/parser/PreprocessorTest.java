package com.weighbridge.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weighbridge.parser.model.OcrInput;
import com.weighbridge.parser.model.ProcessedLine;
import com.weighbridge.parser.pipeline.Preprocessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreprocessorTest {

    private Preprocessor preprocessor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        preprocessor = new Preprocessor();
        objectMapper = new ObjectMapper();
    }

    private OcrInput loadFixture(String filename) throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/" + filename);
        assertThat(is).as("Fixture file %s should exist", filename).isNotNull();
        return objectMapper.readValue(is, OcrInput.class);
    }

    // ── Step 2: Whitespace normalization ──

    @Test
    @DisplayName("한글 자모 사이 공백 제거: '거 래 처' → '거래처'")
    void shouldNormalizeKoreanSpaces() {
        assertThat(Preprocessor.removeKoreanSpaces("거 래 처")).isEqualTo("거래처");
        assertThat(Preprocessor.removeKoreanSpaces("실 중 량")).isEqualTo("실중량");
        assertThat(Preprocessor.removeKoreanSpaces("계 량 증 명 서")).isEqualTo("계량증명서");
        assertThat(Preprocessor.removeKoreanSpaces("중 량:")).isEqualTo("중량:");
    }

    @Test
    @DisplayName("숫자-단위 사이 공백은 유지")
    void shouldKeepSpaceBetweenNumberAndUnit() {
        assertThat(Preprocessor.removeKoreanSpaces("12,480 kg")).isEqualTo("12,480 kg");
        assertThat(Preprocessor.removeKoreanSpaces("5,010 kg")).isEqualTo("5,010 kg");
    }

    @Test
    @DisplayName("sample_01 전체 전처리: 공백 정규화 검증")
    void shouldNormalizeWhitespaceInSample01() throws Exception {
        OcrInput input = loadFixture("sample_01.json");
        List<ProcessedLine> result = preprocessor.process(input);

        // "거 래 처: 곰욕환경폐기물" → "거래처: 곰욕환경폐기물"
        assertThat(result.stream().anyMatch(l -> l.text().contains("거래처:"))).isTrue();
        assertThat(result.stream().noneMatch(l -> l.text().contains("거 래 처"))).isTrue();

        // "실 중 량: 5,010 kg" → "실중량: 5,010 kg"
        assertThat(result.stream().anyMatch(l -> l.text().contains("실중량:"))).isTrue();

        // "계 량 증 명 서" → "계량증명서"
        assertThat(result.stream().anyMatch(l -> l.text().equals("계량증명서"))).isTrue();
    }

    // ── Step 3: Noise marking ──

    @Test
    @DisplayName("저신뢰 토큰 라인에 isNoiseCandidate 마킹")
    void shouldMarkNoiseCandidates() throws Exception {
        OcrInput input = loadFixture("sample_01.json");
        List<ProcessedLine> result = preprocessor.process(input);

        // "품종명랑 05:26:18 12,480 kg" - "품종명랑" confidence 0.1857 → isNoiseCandidate: true
        ProcessedLine weightLine = result.stream()
                .filter(l -> l.text().contains("12,480 kg"))
                .findFirst()
                .orElseThrow();
        assertThat(weightLine.isNoiseCandidate()).isTrue();
        assertThat(weightLine.minWordConfidence()).isLessThan(0.3);
    }

    @Test
    @DisplayName("높은 신뢰도 라인은 isNoiseCandidate가 false")
    void shouldNotMarkHighConfidenceLines() throws Exception {
        OcrInput input = loadFixture("sample_01.json");
        List<ProcessedLine> result = preprocessor.process(input);

        // "차량번호: 8713" - all words > 0.9 → isNoiseCandidate: false
        ProcessedLine vehicleLine = result.stream()
                .filter(l -> l.text().contains("차량번호"))
                .findFirst()
                .orElseThrow();
        assertThat(vehicleLine.isNoiseCandidate()).isFalse();
    }

    // ── Step 4: Line merging ──

    @Test
    @DisplayName("중량 라벨 + 중량 값 라인 병합: '중량:' + '05:36:01 7,470 kg'")
    void shouldMergeWeightLabelAndValue() throws Exception {
        OcrInput input = loadFixture("sample_01.json");
        List<ProcessedLine> result = preprocessor.process(input);

        // "중량:" + "05:36:01 7,470 kg" → merged into "중량: 05:36:01 7,470 kg"
        assertThat(result.stream()
                .anyMatch(l -> l.text().contains("중량:") && l.text().contains("7,470 kg")))
                .isTrue();

        // Standalone "05:36:01 7,470 kg" without label should not exist
        assertThat(result.stream()
                .noneMatch(l -> l.text().equals("05:36:01 7,470 kg")))
                .isTrue();
    }

    @Test
    @DisplayName("라벨 조각 제거: '명:' 단독 라인 제거")
    void shouldRemoveLabelFragments() throws Exception {
        OcrInput input = loadFixture("sample_01.json");
        List<ProcessedLine> result = preprocessor.process(input);

        // "명:" should be removed as a label fragment
        assertThat(result.stream()
                .noneMatch(l -> l.text().trim().equals("명:")))
                .isTrue();
    }

    @Test
    @DisplayName("오병합 방지: TEL + 전화번호 라인은 병합되지 않음")
    void shouldNotMergeTelWithPhoneNumber() {
        // Create a test input with TEL: followed by phone number
        OcrInput input = new OcrInput(null, List.of(
                new OcrInput.Line("TEL:", List.of(
                        new OcrInput.Word("TEL:", 0.95)
                )),
                new OcrInput.Line("031-354-7778", List.of(
                        new OcrInput.Word("031-354-7778", 0.92)
                ))
        ));

        List<ProcessedLine> result = preprocessor.process(input);

        // TEL: and phone number should NOT be merged
        assertThat(result.stream()
                .noneMatch(l -> l.text().contains("TEL:") && l.text().contains("031-354-7778")))
                .isTrue();
    }

    @Test
    @DisplayName("오병합 방지: GPS 좌표 라인은 병합되지 않음")
    void shouldNotMergeWithGpsLine() {
        OcrInput input = new OcrInput(null, List.of(
                new OcrInput.Line("중량:", List.of(
                        new OcrInput.Word("중량:", 0.95)
                )),
                new OcrInput.Line("37.105317, 127.375673", List.of(
                        new OcrInput.Word("37.105317,", 0.89),
                        new OcrInput.Word("127.375673", 0.85)
                ))
        ));

        List<ProcessedLine> result = preprocessor.process(input);

        // Weight label and GPS should NOT be merged
        assertThat(result.stream()
                .noneMatch(l -> l.text().contains("중량:") && l.text().contains("37.105317")))
                .isTrue();
    }

    // ── text fallback ──

    @Test
    @DisplayName("lines[]가 없을 때 text 필드 fallback")
    void shouldFallbackToTextField() {
        OcrInput input = new OcrInput(
                "계량증명서\n차량번호: 8713\n실중량: 5,010 kg",
                null
        );

        List<ProcessedLine> result = preprocessor.process(input);

        assertThat(result).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.stream().anyMatch(l -> l.text().equals("계량증명서"))).isTrue();
        assertThat(result.stream().anyMatch(l -> l.text().contains("차량번호"))).isTrue();
    }

    @Test
    @DisplayName("빈 입력 처리")
    void shouldHandleEmptyInput() {
        OcrInput input = new OcrInput(null, null);
        List<ProcessedLine> result = preprocessor.process(input);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("빈 lines[] 처리")
    void shouldHandleEmptyLines() {
        OcrInput input = new OcrInput(null, List.of());
        List<ProcessedLine> result = preprocessor.process(input);
        assertThat(result).isEmpty();
    }
}
