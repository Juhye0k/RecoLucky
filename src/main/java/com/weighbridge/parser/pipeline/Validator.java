package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Step 4: 교차 검증.
 *
 * 파싱된 문서의 필드 일관성과 유효성을 검증한다.
 */
public class Validator {

    private static final Logger log = LoggerFactory.getLogger(Validator.class);

    public ParsedDocument validate(ParsedDocument doc) {
        List<String> fieldWarnings = new ArrayList<>();
        Map<String, Object> updatedFields = new LinkedHashMap<>(doc.fields());
        boolean isConsistent = true;
        boolean isActionable = true;
        Map<String, Object> weightArithmetic = null;
        boolean weightRolesUnresolved = doc.validation() != null
                && doc.validation().weightRolesUnresolved();

        WeightField gross = getWeight(updatedFields, "gross_weight");
        WeightField tare = getWeight(updatedFields, "tare_weight");
        WeightField net = getWeight(updatedFields, "net_weight");

        // 규칙 1: 중량 산술 (gross - tare = net)
        weightArithmetic = checkWeightArithmetic(gross, tare, net, updatedFields, fieldWarnings);

        // 규칙 2: 중량 양수 검증
        checkWeightPositive(gross, "gross_weight", updatedFields, fieldWarnings);
        checkWeightPositive(tare, "tare_weight", updatedFields, fieldWarnings);
        checkWeightPositive(net, "net_weight", updatedFields, fieldWarnings);

        // 규칙 3: 총중량 최대값
        checkGrossMaximum(gross, tare, net, updatedFields, fieldWarnings);

        // 규칙 4: 날짜 유효성
        boolean dateValid = checkDateValidity(updatedFields, fieldWarnings);

        // 규칙 5: 필수 필드
        boolean docRequired = checkDocumentRequiredFields(updatedFields, fieldWarnings);
        boolean autoRequired = checkAutoProcessingFields(gross, tare, net, fieldWarnings);

        // 문서 레벨 판정
        boolean arithmeticPassed = weightArithmetic != null
                && Boolean.TRUE.equals(weightArithmetic.get("passed"));

        if (!docRequired) isConsistent = false;
        if (!dateValid) isConsistent = false;
        if (weightArithmetic != null && !arithmeticPassed) {
            isConsistent = false;
        }
        if (hasErrorStatus(updatedFields)) isConsistent = false;

        if (!isConsistent) isActionable = false;
        if (!autoRequired) isActionable = false;
        if (weightRolesUnresolved) isActionable = false;

        Map<String, String> inferencePath = doc.validation() != null
                ? doc.validation().weightInferencePath() : null;

        Map<String, Object> requiredFields = new LinkedHashMap<>();
        requiredFields.put("measurement_date", hasField(updatedFields, "measurement_date"));
        requiredFields.put("vehicle_number", hasField(updatedFields, "vehicle_number"));
        requiredFields.put("gross_weight", hasWeightField(gross));
        requiredFields.put("tare_weight", hasWeightField(tare));
        requiredFields.put("net_weight", hasWeightField(net));

        ValidationResult validation = new ValidationResult(
                isConsistent, isActionable, weightArithmetic,
                weightRolesUnresolved, inferencePath,
                requiredFields, fieldWarnings.isEmpty() ? null : fieldWarnings
        );

        log.info("검증 완료: is_consistent={}, is_actionable={}", isConsistent, isActionable);
        if (!fieldWarnings.isEmpty()) {
            for (String w : fieldWarnings) {
                log.warn("검증 위반: {}", w);
            }
        }

        return new ParsedDocument(
                doc.documentType(), doc.sourceFile(), updatedFields,
                doc.unassignedWeights(), doc.extraWeights(),
                doc.resolutionHint(), validation
        );
    }

    // ─────────────────────────────────────────────
    //  규칙 1: 중량 산술
    // ─────────────────────────────────────────────

    private Map<String, Object> checkWeightArithmetic(
            WeightField gross, WeightField tare, WeightField net,
            Map<String, Object> fields, List<String> warnings) {

        if (gross.value() == null || tare.value() == null || net.value() == null) {
            return null;
        }

        int expected = gross.value() - tare.value();
        int delta = Math.abs(expected - net.value());
        boolean passed = delta == 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", passed);
        result.put("gross", gross.value());
        result.put("tare", tare.value());
        result.put("net", net.value());
        result.put("expected_net", expected);
        result.put("delta", delta);

        if (!passed) {
            String msg = String.format("중량 산술 불일치: %d - %d = %d (expected %d, delta=%d)",
                    gross.value(), tare.value(), net.value(), expected, delta);
            warnings.add(msg);

            fields.put("gross_weight", addWarning(gross, Severity.HIGH, msg));
            fields.put("tare_weight", addWarning(tare, Severity.HIGH, msg));
            fields.put("net_weight", addWarning(net, Severity.HIGH, msg));
        }

        return result;
    }

    // ─────────────────────────────────────────────
    //  규칙 2: 중량 양수
    // ─────────────────────────────────────────────

    private void checkWeightPositive(WeightField wf, String fieldName,
                                      Map<String, Object> fields, List<String> warnings) {
        if (wf.value() == null) return;

        if (wf.value() < 0) {
            String msg = fieldName + " 음수: " + wf.value();
            warnings.add(msg);
            fields.put(fieldName, withError(wf, msg));
        } else if (wf.value() == 0) {
            String msg = fieldName + " 값이 0";
            warnings.add(msg);
            fields.put(fieldName, addWarning(wf, Severity.MEDIUM, msg));
        }
    }

    // ─────────────────────────────────────────────
    //  규칙 3: 총중량 최대값
    // ─────────────────────────────────────────────

    private void checkGrossMaximum(WeightField gross, WeightField tare, WeightField net,
                                    Map<String, Object> fields, List<String> warnings) {
        if (gross.value() == null) return;

        if (tare.value() != null && gross.value() < tare.value()) {
            String msg = "총중량(" + gross.value() + ") < 공차중량(" + tare.value() + ")";
            warnings.add(msg);
            fields.put("gross_weight", withError(gross, msg));
            fields.put("tare_weight", withError(tare, msg));
        }
        if (net.value() != null && gross.value() < net.value()) {
            String msg = "총중량(" + gross.value() + ") < 실중량(" + net.value() + ")";
            warnings.add(msg);
            fields.put("gross_weight", withError(gross, msg));
            fields.put("net_weight", withError(net, msg));
        }
    }

    // ─────────────────────────────────────────────
    //  규칙 4: 날짜 유효성
    // ─────────────────────────────────────────────

    private boolean checkDateValidity(Map<String, Object> fields, List<String> warnings) {
        Object dateObj = fields.get("measurement_date");
        if (!(dateObj instanceof BaseField bf) || bf.value() == null) return true;

        String dateStr = bf.value().trim();
        try {
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            if (date.isAfter(LocalDate.now())) {
                String msg = "계량일자가 미래 날짜: " + dateStr;
                warnings.add(msg);
                fields.put("measurement_date", BaseField.warning(bf.value(), bf.rawLabel(),
                        bf.rawValue(), bf.ocrConfidence(), Severity.MEDIUM, msg, bf.sourceLineIndex()));
                return true;
            }
            return true;
        } catch (DateTimeParseException e) {
            String msg = "유효하지 않은 날짜: " + dateStr;
            warnings.add(msg);
            fields.put("measurement_date", new BaseField(bf.value(), bf.rawLabel(),
                    bf.rawValue(), bf.ocrConfidence(), FieldStatus.ERROR, null, msg, bf.sourceLineIndex()));
            return false;
        }
    }

    // ─────────────────────────────────────────────
    //  규칙 5: 필수 필드
    // ─────────────────────────────────────────────

    private boolean checkDocumentRequiredFields(Map<String, Object> fields, List<String> warnings) {
        boolean ok = true;

        if (!hasField(fields, "measurement_date")) {
            warnings.add("필수 필드 누락: measurement_date");
            ok = false;
        }
        if (!hasField(fields, "vehicle_number")) {
            warnings.add("필수 필드 누락: vehicle_number");
            ok = false;
        }

        if (!hasField(fields, "customer")) {
            warnings.add("권장 필드 누락: customer");
        }
        if (!hasField(fields, "issuer")) {
            warnings.add("권장 필드 누락: issuer");
        }

        return ok;
    }

    private boolean checkAutoProcessingFields(WeightField gross, WeightField tare,
                                               WeightField net, List<String> warnings) {
        boolean ok = true;

        if (!hasWeightField(gross)) {
            warnings.add("자동처리 필수 필드 누락/미확정: gross_weight");
            ok = false;
        }
        if (!hasWeightField(tare)) {
            warnings.add("자동처리 필수 필드 누락/미확정: tare_weight");
            ok = false;
        }
        if (!hasWeightField(net)) {
            warnings.add("자동처리 필수 필드 누락/미확정: net_weight");
            ok = false;
        }

        return ok;
    }

    // ─────────────────────────────────────────────
    //  유틸리티
    // ─────────────────────────────────────────────

    private WeightField getWeight(Map<String, Object> fields, String key) {
        Object obj = fields.get(key);
        if (obj instanceof WeightField wf) return wf;
        return WeightField.missing();
    }

    private boolean hasField(Map<String, Object> fields, String key) {
        Object obj = fields.get(key);
        if (obj == null) return false;
        if (obj instanceof BaseField bf) {
            return bf.status() != FieldStatus.MISSING && bf.value() != null;
        }
        if (obj instanceof WeightField wf) {
            return hasWeightField(wf);
        }
        return true;
    }

    private boolean hasWeightField(WeightField wf) {
        return wf != null && wf.value() != null
                && wf.status() != FieldStatus.MISSING
                && wf.status() != FieldStatus.UNRESOLVED;
    }

    private boolean hasErrorStatus(Map<String, Object> fields) {
        for (Object v : fields.values()) {
            if (v instanceof WeightField wf && wf.status() == FieldStatus.ERROR) return true;
            if (v instanceof BaseField bf && bf.status() == FieldStatus.ERROR) return true;
        }
        return false;
    }

    private WeightField addWarning(WeightField wf, Severity severity, String msg) {
        if (wf.status() == FieldStatus.ERROR) return wf;
        return new WeightField(
                wf.value(), wf.unit(), wf.time(), wf.rawLabel(), wf.rawValue(),
                wf.ocrConfidence(), wf.assignmentConfidence(), wf.inferredBy(),
                FieldStatus.WARNING, severity, msg, wf.sourceLineIndex()
        );
    }

    private WeightField withError(WeightField wf, String msg) {
        return new WeightField(
                wf.value(), wf.unit(), wf.time(), wf.rawLabel(), wf.rawValue(),
                wf.ocrConfidence(), wf.assignmentConfidence(), wf.inferredBy(),
                FieldStatus.ERROR, null, msg, wf.sourceLineIndex()
        );
    }
}
