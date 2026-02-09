package com.weighbridge.parser;

import com.weighbridge.parser.model.*;
import com.weighbridge.parser.pipeline.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerTest {

    // ── 날짜 정규화 ──

    @Nested
    @DisplayName("3-1: 날짜 정규화")
    class DateNormalizationTest {

        @Test
        @DisplayName("'2025.12.01' → '2025-12-01'")
        void dotSeparator() {
            assertThat(Normalizer.normalizeDate("2025.12.01")).isEqualTo("2025-12-01");
        }

        @Test
        @DisplayName("'2025/12/01' → '2025-12-01'")
        void slashSeparator() {
            assertThat(Normalizer.normalizeDate("2025/12/01")).isEqualTo("2025-12-01");
        }

        @Test
        @DisplayName("'2026-02-02' → 변경 없음")
        void alreadyNormalized() {
            assertThat(Normalizer.normalizeDate("2026-02-02")).isEqualTo("2026-02-02");
        }

        @Test
        @DisplayName("'2026-02-02-00004' → '2026-02-02' (일련번호 분리)")
        void serialNumberSeparation() {
            assertThat(Normalizer.normalizeDate("2026-02-02-00004")).isEqualTo("2026-02-02");
        }

        @Test
        @DisplayName("null → null")
        void nullInput() {
            assertThat(Normalizer.normalizeDate(null)).isNull();
        }
    }

    // ── 시간 정규화 ──

    @Nested
    @DisplayName("3-2: 시간 정규화")
    class TimeNormalizationTest {

        @Test
        @DisplayName("'02:07' → '02:07:00'")
        void hhmmToHhmmss() {
            assertThat(Normalizer.normalizeTime("02:07")).isEqualTo("02:07:00");
        }

        @Test
        @DisplayName("'11시 33분' → '11:33:00'")
        void koreanTimeFormat() {
            assertThat(Normalizer.normalizeTime("11시 33분")).isEqualTo("11:33:00");
        }

        @Test
        @DisplayName("'5:26' → '05:26:00' (1자리 시간 패딩)")
        void singleDigitHour() {
            assertThat(Normalizer.normalizeTime("5:26")).isEqualTo("05:26:00");
        }

        @Test
        @DisplayName("'5:26:18' → '05:26:18'")
        void singleDigitHourWithSec() {
            assertThat(Normalizer.normalizeTime("5:26:18")).isEqualTo("05:26:18");
        }

        @Test
        @DisplayName("'05:37:55' → 변경 없음")
        void alreadyNormalized() {
            assertThat(Normalizer.normalizeTime("05:37:55")).isEqualTo("05:37:55");
        }

        @Test
        @DisplayName("null → null")
        void nullInput() {
            assertThat(Normalizer.normalizeTime(null)).isNull();
        }
    }

    // ── 중량 정규화 ──

    @Nested
    @DisplayName("3-3: 중량 정규화")
    class WeightNormalizationTest {

        @Test
        @DisplayName("'13 460' → 13460")
        void spaceRemoval() {
            assertThat(Normalizer.normalizeWeight("13 460")).isEqualTo(13460);
        }

        @Test
        @DisplayName("'5,010' → 5010")
        void commaRemoval() {
            assertThat(Normalizer.normalizeWeight("5,010")).isEqualTo(5010);
        }

        @Test
        @DisplayName("'12,480' → 12480")
        void normalComma() {
            assertThat(Normalizer.normalizeWeight("12,480")).isEqualTo(12480);
        }

        @Test
        @DisplayName("'1234.56' → 1235 (반올림)")
        void decimalRounding() {
            assertThat(Normalizer.normalizeWeight("1234.56")).isEqualTo(1235);
        }

        @Test
        @DisplayName("'1234.4' → 1234 (반올림 내림)")
        void decimalRoundingDown() {
            assertThat(Normalizer.normalizeWeight("1234.4")).isEqualTo(1234);
        }
    }

    // ── 단위 정규화 ──

    @Nested
    @DisplayName("3-4: 단위 정규화")
    class UnitNormalizationTest {

        @Test
        @DisplayName("'㎏' → 'kg'")
        void specialKg() {
            assertThat(Normalizer.normalizeUnit("㎏")).isEqualTo("kg");
        }

        @Test
        @DisplayName("'KG' → 'kg'")
        void uppercaseKg() {
            assertThat(Normalizer.normalizeUnit("KG")).isEqualTo("kg");
        }

        @Test
        @DisplayName("'k9' → 'kg'")
        void k9() {
            assertThat(Normalizer.normalizeUnit("k9")).isEqualTo("kg");
        }

        @Test
        @DisplayName("'Kg' → 'kg'")
        void mixedCaseKg() {
            assertThat(Normalizer.normalizeUnit("Kg")).isEqualTo("kg");
        }

        @Test
        @DisplayName("null → 'kg'")
        void nullUnit() {
            assertThat(Normalizer.normalizeUnit(null)).isEqualTo("kg");
        }
    }

    // ── 텍스트 정규화 (옵션) ──

    @Nested
    @DisplayName("3-5: 텍스트 약한 정규화")
    class TextNormalizationTest {

        @Test
        @DisplayName("법인 표기 제거: '동우바이오(주)' → '동우바이오'")
        void removeCorp() {
            assertThat(Normalizer.normalizeTextValue("동우바이오(주)")).isEqualTo("동우바이오");
        }

        @Test
        @DisplayName("㈜ 제거: '㈜한진' → '한진'")
        void removeCorpSymbol() {
            assertThat(Normalizer.normalizeTextValue("㈜한진")).isEqualTo("한진");
        }

        @Test
        @DisplayName("연속 공백 → 단일 공백")
        void multipleSpaces() {
            assertThat(Normalizer.normalizeTextValue("계량   증명서")).isEqualTo("계량 증명서");
        }

        @Test
        @DisplayName("null → null")
        void nullInput() {
            assertThat(Normalizer.normalizeTextValue(null)).isNull();
        }
    }

    // ── 전체 파이프라인 정규화 ──

    @Nested
    @DisplayName("전체 파이프라인 정규화")
    class FullNormalizationTest {

        @Test
        @DisplayName("WeightField 시간 정규화 (5:26 → 05:26:00)")
        void weightFieldTimeNormalization() {
            WeightField wf = new WeightField(
                    12480, "KG", "5:26",
                    "총중량", "12,480", 0.95, 1.0, InferredBy.LABEL,
                    FieldStatus.OK, null, null, 0
            );
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("gross_weight", wf);
            fields.put("tare_weight", WeightField.missing());
            fields.put("net_weight", WeightField.missing());

            ParsedDocument doc = new ParsedDocument(
                    null, "test", fields, List.of(), List.of(), null, null
            );
            Normalizer normalizer = new Normalizer();
            ParsedDocument normalized = normalizer.normalize(doc);

            WeightField result = (WeightField) normalized.fields().get("gross_weight");
            assertThat(result.time()).isEqualTo("05:26:00");
            assertThat(result.unit()).isEqualTo("kg");
        }

        @Test
        @DisplayName("BaseField 날짜 정규화 (2025.12.01 → 2025-12-01)")
        void baseFieldDateNormalization() {
            BaseField bf = BaseField.ok("2025.12.01", "계량일자", "2025.12.01", 0.95, 0);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("measurement_date", bf);
            fields.put("gross_weight", WeightField.missing());
            fields.put("tare_weight", WeightField.missing());
            fields.put("net_weight", WeightField.missing());

            ParsedDocument doc = new ParsedDocument(
                    null, "test", fields, List.of(), List.of(), null, null
            );
            Normalizer normalizer = new Normalizer();
            ParsedDocument normalized = normalizer.normalize(doc);

            BaseField result = (BaseField) normalized.fields().get("measurement_date");
            assertThat(result.value()).isEqualTo("2025-12-01");
        }

        @Test
        @DisplayName("텍스트 정규화 OFF (기본) → 값 변경 없음")
        void textNormalizationOff() {
            BaseField bf = BaseField.ok("동우바이오(주)", null, "동우바이오(주)", 0.95, 0);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("issuer", bf);
            fields.put("gross_weight", WeightField.missing());
            fields.put("tare_weight", WeightField.missing());
            fields.put("net_weight", WeightField.missing());

            ParsedDocument doc = new ParsedDocument(
                    null, "test", fields, List.of(), List.of(), null, null
            );
            Normalizer normalizer = new Normalizer(false);
            ParsedDocument normalized = normalizer.normalize(doc);

            BaseField result = (BaseField) normalized.fields().get("issuer");
            assertThat(result.value()).isEqualTo("동우바이오(주)");
        }

        @Test
        @DisplayName("텍스트 정규화 ON → 법인 표기 제거")
        void textNormalizationOn() {
            BaseField bf = BaseField.ok("동우바이오(주)", null, "동우바이오(주)", 0.95, 0);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("issuer", bf);
            fields.put("gross_weight", WeightField.missing());
            fields.put("tare_weight", WeightField.missing());
            fields.put("net_weight", WeightField.missing());

            ParsedDocument doc = new ParsedDocument(
                    null, "test", fields, List.of(), List.of(), null, null
            );
            Normalizer normalizer = new Normalizer(true);
            ParsedDocument normalized = normalizer.normalize(doc);

            BaseField result = (BaseField) normalized.fields().get("issuer");
            assertThat(result.value()).isEqualTo("동우바이오");
        }

        @Test
        @DisplayName("MISSING/UNRESOLVED 필드는 정규화 skip")
        void skipMissingUnresolved() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("gross_weight", WeightField.missing());
            fields.put("tare_weight", WeightField.unresolved("미확정"));
            fields.put("net_weight", WeightField.missing());

            ParsedDocument doc = new ParsedDocument(
                    null, "test", fields, List.of(), List.of(), null, null
            );
            Normalizer normalizer = new Normalizer();
            ParsedDocument normalized = normalizer.normalize(doc);

            WeightField gross = (WeightField) normalized.fields().get("gross_weight");
            WeightField tare = (WeightField) normalized.fields().get("tare_weight");
            assertThat(gross.status()).isEqualTo(FieldStatus.MISSING);
            assertThat(tare.status()).isEqualTo(FieldStatus.UNRESOLVED);
        }
    }

    // ── ParameterizedTest: 날짜 변형 ──

    @Nested
    @DisplayName("ParameterizedTest: 날짜/시간/단위 변형")
    class ParameterizedNormalizationTest {

        @ParameterizedTest(name = "날짜 ''{0}'' → ''{1}''")
        @CsvSource({
                "2025.12.01, 2025-12-01",
                "2025/12/01, 2025-12-01",
                "2025-12-01, 2025-12-01",
                "2026.01.15, 2026-01-15",
                "2026/01/15, 2026-01-15"
        })
        void dateVariations(String input, String expected) {
            assertThat(Normalizer.normalizeDate(input)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "시간 ''{0}'' → ''{1}''")
        @CsvSource({
                "02:07,     02:07:00",
                "5:26,      05:26:00",
                "5:26:18,   05:26:18",
                "05:37:55,  05:37:55",
                "23:59,     23:59:00",
                "0:00,      00:00:00"
        })
        void timeVariations(String input, String expected) {
            assertThat(Normalizer.normalizeTime(input)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "단위 ''{0}'' → ''kg''")
        @CsvSource({
                "kg", "KG", "Kg", "㎏", "k9", "kq"
        })
        void unitVariations(String input) {
            assertThat(Normalizer.normalizeUnit(input)).isEqualTo("kg");
        }

        @ParameterizedTest(name = "중량 ''{0}'' → {1}")
        @CsvSource({
                "'12,480', 12480",
                "'5,010',  5010",
                "'13 460', 13460",
                "7470,     7470",
                "'1,000',  1000"
        })
        void weightVariations(String input, int expected) {
            assertThat(Normalizer.normalizeWeight(input)).isEqualTo(expected);
        }
    }

    // ── 엣지 케이스: 빈 값, 경계값 ──

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCaseTest {

        @Test
        @DisplayName("normalizeWeight: null → 0")
        void weightNull() {
            assertThat(Normalizer.normalizeWeight(null)).isEqualTo(0);
        }

        @Test
        @DisplayName("normalizeWeight: '0' → 0")
        void weightZero() {
            assertThat(Normalizer.normalizeWeight("0")).isEqualTo(0);
        }

        @Test
        @DisplayName("normalizeDate: 빈 문자열은 그대로 반환")
        void dateEmpty() {
            assertThat(Normalizer.normalizeDate("")).isEqualTo("");
        }

        @Test
        @DisplayName("normalizeTime: 빈 문자열은 그대로 반환")
        void timeEmpty() {
            assertThat(Normalizer.normalizeTime("")).isEqualTo("");
        }
    }
}
