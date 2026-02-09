package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.config.AliasMatchingUtils;
import com.weighbridge.parser.config.FieldAliases;
import com.weighbridge.parser.config.Thresholds;
import com.weighbridge.parser.model.ProcessedLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extractor Step 1: 값 패턴 기반 라인 분류.
 *
 * 핵심 원칙: 라벨보다 값 패턴을 우선한다 (Value-First Classification).
 */
public class Extractor {

    private static final Logger log = LoggerFactory.getLogger(Extractor.class);

    // ── label_delimiter_colon 판정 ──
    private static final Pattern LABEL_COLON_PATTERN =
            Pattern.compile("^(?!\\s*\\d{1,2}:\\d{2})\\s*.{1,30}[:：]\\s*");

    // ── kg 단위 패턴 (OCR 깨진 형태 포함) ──
    private static final Pattern KG_UNIT_PATTERN =
            Pattern.compile("(kg|㎏|KG|Kg|k9|kq|K\\s*G|k\\s*g)(?![가-힣a-zA-Z])");

    // ── guard rule ──
    private static final Pattern GUARD_RULE_PATTERN =
            Pattern.compile("원/kg|kg당|단가", Pattern.CASE_INSENSITIVE);

    // ── 3~6자리 정수 패턴 ──
    private static final Pattern WEIGHT_DIGITS_PATTERN =
            Pattern.compile("(?<![:\\d])(\\d{1,3}[,]\\d{3}|\\d{1,3}\\s\\d{3}|\\d{3,6})(?![:\\d])");

    // ── timestamp: YYYY-MM-DD HH:MM(:SS) ──
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\d{4}[-./]\\d{2}[-./]\\d{2}\\s+\\d{1,2}:\\d{2}(:\\d{2})?");

    // ── date: YYYY-MM-DD ──
    private static final Pattern DATE_PATTERN =
            Pattern.compile("\\d{4}[-./]\\d{2}[-./]\\d{2}");

    // ── GPS: 소수점 좌표 ──
    private static final Pattern GPS_PATTERN =
            Pattern.compile("\\d+\\.\\d{4,},\\s*\\d+\\.\\d{4,}");

    // ── time + 정수 패턴 (약후보용) ──
    private static final Pattern TIME_PLUS_INT_PATTERN =
            Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?\\s+[\\d,\\s]{3,}");

    // ── issuer 패턴 ──
    private static final Pattern ISSUER_PATTERN =
            Pattern.compile("\\(주\\)|\\(株\\)|㈜|Co\\.|C&S");

    private static final Pattern ADDRESS_PATTERN =
            Pattern.compile("[가-힣]{2,}[시군구동면리읍]|\\d+로|\\d+길|\\d+번길");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\d{2,3}-\\d{2,4}-\\d{4}");

    private static final Pattern ZIPCODE_PATTERN =
            Pattern.compile("(?<!\\d)\\d{5}(?!\\d)");

    private static final Set<String> ISSUER_NEGATIVE_LEXICON = Set.of(
            "관리팀", "담당", "센터", "사무실", "문의",
            "TEL", "FAX", "대표자", "주소",
            "사업자", "계근", "차량", "품명", "등록번호"
    );

    public List<ClassifiedLine> classifyLines(List<ProcessedLine> lines) {
        List<ClassifiedLine> classified = new ArrayList<>();
        for (ProcessedLine line : lines) {
            classified.add(classifySingleLine(line));
        }
        promoteWeightCandidates(classified);

        String distribution = classified.stream()
                .collect(Collectors.groupingBy(ClassifiedLine::type, Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        log.info("라인 분류 완료: {}", distribution);

        return classified;
    }

    private ClassifiedLine classifySingleLine(ProcessedLine line) {
        String text = line.text();
        boolean hasLabelColon = hasLabelDelimiterColon(text);
        boolean hasKgUnit = hasKgUnit(text);
        boolean guardRuleHit = GUARD_RULE_PATTERN.matcher(text).find();

        if (hasLabelColon) {
            if (hasKgUnit && !guardRuleHit) {
                return new ClassifiedLine(line, LineType.WEIGHT_EVENT, false, null);
            }
            return new ClassifiedLine(line, LineType.LABEL_VALUE_LINE, false, null);
        }

        if (TIMESTAMP_PATTERN.matcher(text).find()) {
            return new ClassifiedLine(line, LineType.TIMESTAMP_LINE, false, null);
        }
        if (GPS_PATTERN.matcher(text).find()) {
            return new ClassifiedLine(line, LineType.GPS_LINE, false, null);
        }
        if (hasKgUnit && !guardRuleHit && hasWeightDigits(text)) {
            return new ClassifiedLine(line, LineType.WEIGHT_EVENT, false, null);
        }
        if (!guardRuleHit && TIME_PLUS_INT_PATTERN.matcher(text).find() && hasWeightDigits(text)) {
            return new ClassifiedLine(line, LineType.WEIGHT_EVENT, true,
                    "kg 단위 누락, 패턴 기반 추정");
        }
        if (DATE_PATTERN.matcher(text).find()) {
            return new ClassifiedLine(line, LineType.DATE_LINE, false, null);
        }
        if (isIssuerLine(text)) {
            return new ClassifiedLine(line, LineType.ISSUER_LINE, false, null);
        }
        return new ClassifiedLine(line, LineType.ETC_LINE, false, null);
    }

    public static boolean hasLabelDelimiterColon(String text) {
        String stripped = text.replaceAll("\\d{1,2}:\\d{2}(:\\d{2})?", "");
        return LABEL_COLON_PATTERN.matcher(stripped).find();
    }

    public static boolean hasKgUnit(String text) {
        return KG_UNIT_PATTERN.matcher(text).find();
    }

    private boolean hasWeightDigits(String text) {
        Matcher m = WEIGHT_DIGITS_PATTERN.matcher(text);
        while (m.find()) {
            String digits = m.group(1).replaceAll("[,\\s]", "");
            if (digits.length() >= Thresholds.WEIGHT_DIGITS_MIN
                    && digits.length() <= Thresholds.WEIGHT_DIGITS_MAX) {
                return true;
            }
        }
        return false;
    }

    private boolean isIssuerLine(String text) {
        if (!ISSUER_PATTERN.matcher(text).find()) return false;
        if (ADDRESS_PATTERN.matcher(text).find()) return false;
        if (PHONE_PATTERN.matcher(text).find()) return false;
        if (ZIPCODE_PATTERN.matcher(text).find()) return false;
        for (String keyword : ISSUER_NEGATIVE_LEXICON) {
            if (text.contains(keyword)) return false;
        }
        return true;
    }

    /**
     * 약후보 승격: Path A만 유지 (문서 내 kg ≥1 + ±2 라인 라벨 힌트).
     * 미승격 → etc_line으로 강등.
     */
    private void promoteWeightCandidates(List<ClassifiedLine> lines) {
        long kgCount = lines.stream()
                .filter(cl -> cl.type() == LineType.WEIGHT_EVENT && !cl.isCandidate())
                .count();

        for (int i = 0; i < lines.size(); i++) {
            ClassifiedLine cl = lines.get(i);
            if (cl.type() != LineType.WEIGHT_EVENT || !cl.isCandidate()) continue;

            if (kgCount >= 1 && hasNearbyWeightLabel(lines, i)) {
                lines.set(i, new ClassifiedLine(cl.line(), LineType.WEIGHT_EVENT, false, null));
            } else {
                lines.set(i, new ClassifiedLine(cl.line(), LineType.ETC_LINE, false, null));
            }
        }
    }

    private boolean hasNearbyWeightLabel(List<ClassifiedLine> lines, int targetIndex) {
        int start = Math.max(0, targetIndex - 2);
        int end = Math.min(lines.size() - 1, targetIndex + 2);

        for (int i = start; i <= end; i++) {
            if (i == targetIndex) continue;
            String text = lines.get(i).text();
            if (AliasMatchingUtils.matchesAny(text, FieldAliases.WEIGHT_ALIASES,
                    Thresholds.FUZZY_LABEL_WEAK)) {
                return true;
            }
        }
        return false;
    }
}
