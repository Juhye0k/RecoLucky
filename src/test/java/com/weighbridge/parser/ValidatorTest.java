package com.weighbridge.parser;

import com.weighbridge.parser.model.*;
import com.weighbridge.parser.pipeline.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = new Validator();
    }

    private ParsedDocument buildDoc(WeightField gross, WeightField tare, WeightField net,
                                     Map<String, Object> extraFields) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("gross_weight", gross);
        fields.put("tare_weight", tare);
        fields.put("net_weight", net);
        if (extraFields != null) fields.putAll(extraFields);

        ValidationResult tempValidation = new ValidationResult(
                true, true, null, false, null, null, null
        );
        return new ParsedDocument(null, "test", fields, List.of(), List.of(), null, tempValidation);
    }

    private WeightField weight(int value) {
        return new WeightField(value, "kg", null, null, String.valueOf(value),
                0.95, 1.0, InferredBy.LABEL, FieldStatus.OK, null, null, 0);
    }

    // ── 규칙 1: 중량 산술 ──

    @Nested
    @DisplayName("규칙 1: 중량 산술 검증")
    class ArithmeticTest {

        @Test
        @DisplayName("12480 - 7470 = 5010 → PASS")
        void arithmeticPass() {
            ParsedDocument doc = buildDoc(weight(12480), weight(7470), weight(5010), null);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().weightArithmetic()).isNotNull();
            assertThat(validated.validation().weightArithmetic().get("passed")).isEqualTo(true);
            assertThat(validated.validation().weightArithmetic().get("delta")).isEqualTo(0);
        }

        @Test
        @DisplayName("12480 - 7470 = 5000 → FAIL (delta=10)")
        void arithmeticFail() {
            ParsedDocument doc = buildDoc(weight(12480), weight(7470), weight(5000), null);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().weightArithmetic().get("passed")).isEqualTo(false);
            assertThat(validated.validation().weightArithmetic().get("delta")).isEqualTo(10);
        }

        @Test
        @DisplayName("산술 불일치 시 세 필드 모두 WARNING(HIGH)")
        void allFieldsWarning() {
            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(2000), null);
            ParsedDocument validated = validator.validate(doc);

            WeightField gross = (WeightField) validated.fields().get("gross_weight");
            WeightField tare = (WeightField) validated.fields().get("tare_weight");
            WeightField net = (WeightField) validated.fields().get("net_weight");

            assertThat(gross.status()).isEqualTo(FieldStatus.WARNING);
            assertThat(gross.severity()).isEqualTo(Severity.HIGH);
            assertThat(tare.status()).isEqualTo(FieldStatus.WARNING);
            assertThat(net.status()).isEqualTo(FieldStatus.WARNING);
        }

        @Test
        @DisplayName("중량 하나라도 null이면 산술 검증 skip")
        void skipIfNull() {
            ParsedDocument doc = buildDoc(weight(10000), WeightField.missing(), weight(5000), null);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().weightArithmetic()).isNull();
        }
    }

    // ── 규칙 2: 중량 양수 ──

    @Nested
    @DisplayName("규칙 2: 중량 양수 검증")
    class PositiveTest {

        @Test
        @DisplayName("값 0 → WARNING(MEDIUM)")
        void zeroWeight() {
            ParsedDocument doc = buildDoc(weight(10000), weight(0), weight(10000), null);
            ParsedDocument validated = validator.validate(doc);

            WeightField tare = (WeightField) validated.fields().get("tare_weight");
            assertThat(tare.status()).isEqualTo(FieldStatus.WARNING);
            assertThat(tare.severity()).isEqualTo(Severity.MEDIUM);
        }

        @Test
        @DisplayName("음수 → ERROR")
        void negativeWeight() {
            ParsedDocument doc = buildDoc(weight(10000), weight(-100), weight(10100), null);
            ParsedDocument validated = validator.validate(doc);

            WeightField tare = (WeightField) validated.fields().get("tare_weight");
            assertThat(tare.status()).isEqualTo(FieldStatus.ERROR);
        }
    }

    // ── 규칙 3: 총중량 최대값 ──

    @Nested
    @DisplayName("규칙 3: 총중량 최대값")
    class GrossMaxTest {

        @Test
        @DisplayName("gross < tare → ERROR")
        void grossLessThanTare() {
            ParsedDocument doc = buildDoc(weight(5000), weight(7000), weight(0), null);
            ParsedDocument validated = validator.validate(doc);

            WeightField gross = (WeightField) validated.fields().get("gross_weight");
            assertThat(gross.status()).isEqualTo(FieldStatus.ERROR);
        }

        @Test
        @DisplayName("gross < net → ERROR")
        void grossLessThanNet() {
            ParsedDocument doc = buildDoc(weight(3000), weight(1000), weight(5000), null);
            ParsedDocument validated = validator.validate(doc);

            WeightField gross = (WeightField) validated.fields().get("gross_weight");
            assertThat(gross.status()).isEqualTo(FieldStatus.ERROR);
        }
    }

    // ── 규칙 4: 날짜 유효성 ──

    @Nested
    @DisplayName("규칙 4: 날짜 유효성")
    class DateValidityTest {

        @Test
        @DisplayName("유효한 날짜 → 통과")
        void validDate() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            BaseField date = (BaseField) validated.fields().get("measurement_date");
            assertThat(date.status()).isNotEqualTo(FieldStatus.ERROR);
        }

        @Test
        @DisplayName("유효하지 않은 날짜 (13월) → ERROR")
        void invalidDate() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-13-01", "계량일자", "2026-13-01", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            BaseField date = (BaseField) validated.fields().get("measurement_date");
            assertThat(date.status()).isEqualTo(FieldStatus.ERROR);
        }
    }

    // ── 규칙 5: 필수 필드 ──

    @Nested
    @DisplayName("규칙 5: 필수 필드")
    class RequiredFieldsTest {

        @Test
        @DisplayName("measurement_date 누락 → is_consistent=false")
        void missingDate() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isConsistent()).isFalse();
        }

        @Test
        @DisplayName("중량 MISSING → is_actionable=false")
        void missingWeight() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), WeightField.missing(), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isActionable()).isFalse();
        }

        @Test
        @DisplayName("모든 필수 필드 OK → is_consistent=true, is_actionable=true")
        void allFieldsPresent() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isConsistent()).isTrue();
            assertThat(validated.validation().isActionable()).isTrue();
        }

        @Test
        @DisplayName("UNRESOLVED → is_actionable=false")
        void unresolvedWeight() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("gross_weight", weight(10000));
            fields.put("tare_weight", WeightField.unresolved("미확정"));
            fields.put("net_weight", weight(3000));
            fields.putAll(extra);

            ValidationResult tempValidation = new ValidationResult(
                    true, true, null, true, null, null, null
            );
            ParsedDocument doc = new ParsedDocument(null, "test", fields,
                    List.of(), List.of(), null, tempValidation);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isActionable()).isFalse();
        }
    }

    // ── is_consistent / is_actionable 조합 ──

    @Nested
    @DisplayName("is_consistent / is_actionable 조합")
    class ConsistencyActionabilityTest {

        @Test
        @DisplayName("산술 통과 + 필수 필드 OK → consistent=true, actionable=true")
        void bothTrue() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isConsistent()).isTrue();
            assertThat(validated.validation().isActionable()).isTrue();
        }

        @Test
        @DisplayName("산술 불일치 → consistent=false, actionable=false")
        void arithmeticFail() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(2000), extra);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isConsistent()).isFalse();
            assertThat(validated.validation().isActionable()).isFalse();
        }

        @Test
        @DisplayName("필수 필드 누락 + 산술 통과 → consistent=false, actionable=false")
        void missingRequiredButArithOk() {
            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), null);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isConsistent()).isFalse();
            assertThat(validated.validation().isActionable()).isFalse();
        }
    }

    // ── 추가 엣지 케이스 ──

    @Nested
    @DisplayName("추가 엣지 케이스")
    class AdditionalEdgeCaseTest {

        @Test
        @DisplayName("모든 중량 MISSING → 산술 검증 skip + is_actionable=false")
        void allWeightsMissing() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(WeightField.missing(), WeightField.missing(),
                    WeightField.missing(), extra);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().weightArithmetic()).isNull();
            assertThat(validated.validation().isActionable()).isFalse();
        }

        @Test
        @DisplayName("required_fields 맵 검증")
        void requiredFieldsMap() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));
            extra.put("vehicle_number", BaseField.ok("8713", "차량번호", "8713", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            Map<String, Object> required = validated.validation().requiredFields();
            assertThat(required).isNotNull();
            assertThat(required.get("measurement_date")).isEqualTo(true);
            assertThat(required.get("vehicle_number")).isEqualTo(true);
            assertThat(required.get("gross_weight")).isEqualTo(true);
            assertThat(required.get("tare_weight")).isEqualTo(true);
            assertThat(required.get("net_weight")).isEqualTo(true);
        }

        @Test
        @DisplayName("vehicle_number 누락 → is_consistent=false")
        void missingVehicle() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("measurement_date", BaseField.ok("2026-01-15", "계량일자", "2026-01-15", 0.95, 0));

            ParsedDocument doc = buildDoc(weight(10000), weight(7000), weight(3000), extra);
            ParsedDocument validated = validator.validate(doc);

            assertThat(validated.validation().isConsistent()).isFalse();
        }
    }
}
