package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 3: 값 정규화.
 *
 * 날짜, 시간, 중량, 단위, 텍스트를 정규화한다.
 * raw_value는 항상 원본 보존.
 */
public class Normalizer {

    private static final Logger log = LoggerFactory.getLogger(Normalizer.class);

    // ── 날짜 패턴: YYYY.MM.DD 또는 YYYY/MM/DD ──
    private static final Pattern DATE_SEPARATOR_PATTERN =
            Pattern.compile("(\\d{4})[./](\\d{2})[./](\\d{2})");

    // ── 날짜 뒤 일련번호 제거: YYYY-MM-DD-NNNNN ──
    private static final Pattern DATE_WITH_SERIAL_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-\\d{3,6}");

    // ── 시간 패턴: HH:MM (초 없음) ──
    private static final Pattern TIME_HHMM_PATTERN =
            Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    // ── 시간 패턴: 한글 시/분 ──
    private static final Pattern TIME_KOREAN_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*시\\s*(\\d{1,2})\\s*분");

    // ── 시간 1자리 시간: H:MM:SS ──
    private static final Pattern TIME_SINGLE_HOUR_PATTERN =
            Pattern.compile("^(\\d):(\\d{2})(:\\d{2})?$");

    // ── 단위 정규화 매핑 ──
    private static final Pattern UNIT_NORMALIZE_PATTERN =
            Pattern.compile("㎏|KG|Kg|k9|kq|K\\s*G|k\\s*g");

    // ── 텍스트 법인 표기 패턴 ──
    private static final Pattern CORP_PATTERN =
            Pattern.compile("\\(주\\)|\\(株\\)|㈜");

    private final boolean normalizeText;

    public Normalizer() {
        this(false);
    }

    public Normalizer(boolean normalizeText) {
        this.normalizeText = normalizeText;
    }

    // ─────────────────────────────────────────────
    //  공개 API
    // ─────────────────────────────────────────────

    /**
     * ParsedDocument의 필드 값들을 정규화한다.
     */
    public ParsedDocument normalize(ParsedDocument doc) {
        Map<String, Object> normalizedFields = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : doc.fields().entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof WeightField wf) {
                normalizedFields.put(fieldName, normalizeWeightField(wf));
            } else if (value instanceof BaseField bf) {
                normalizedFields.put(fieldName, normalizeBaseField(fieldName, bf));
            } else {
                normalizedFields.put(fieldName, value);
            }
        }

        log.info("정규화 완료: {}개 필드", normalizedFields.size());

        return new ParsedDocument(
                doc.documentType(), doc.sourceFile(), normalizedFields,
                doc.unassignedWeights(), doc.extraWeights(),
                doc.resolutionHint(), doc.validation()
        );
    }

    // ─────────────────────────────────────────────
    //  WeightField 정규화
    // ─────────────────────────────────────────────

    private WeightField normalizeWeightField(WeightField wf) {
        if (wf.status() == FieldStatus.MISSING || wf.status() == FieldStatus.UNRESOLVED) {
            return wf;
        }

        // 중량값 정규화 (이미 int이지만 확인)
        Integer normalizedValue = wf.value();

        // 시간 정규화
        String normalizedTime = wf.time() != null ? normalizeTime(wf.time()) : null;

        // 단위 정규화
        String normalizedUnit = normalizeUnit(wf.unit());

        return new WeightField(
                normalizedValue, normalizedUnit, normalizedTime,
                wf.rawLabel(), wf.rawValue(),
                wf.ocrConfidence(), wf.assignmentConfidence(), wf.inferredBy(),
                wf.status(), wf.severity(), wf.message(), wf.sourceLineIndex()
        );
    }

    // ─────────────────────────────────────────────
    //  BaseField 정규화
    // ─────────────────────────────────────────────

    private BaseField normalizeBaseField(String fieldName, BaseField bf) {
        if (bf.status() == FieldStatus.MISSING) return bf;
        if (bf.value() == null) return bf;

        String normalizedValue = bf.value();

        switch (fieldName) {
            case "measurement_date" -> normalizedValue = normalizeDate(bf.value());
            case "issued_at" -> normalizedValue = normalizeTimestamp(bf.value());
            default -> {
                if (normalizeText) {
                    normalizedValue = normalizeTextValue(bf.value());
                }
            }
        }

        return new BaseField(
                normalizedValue, bf.rawLabel(), bf.rawValue(),
                bf.ocrConfidence(), bf.status(), bf.severity(), bf.message(),
                bf.sourceLineIndex()
        );
    }

    // ─────────────────────────────────────────────
    //  날짜 정규화 (3-1)
    // ─────────────────────────────────────────────

    /**
     * 날짜를 YYYY-MM-DD 형식으로 통일.
     */
    public static String normalizeDate(String date) {
        if (date == null) return null;

        // ./ 구분자 → -
        Matcher m = DATE_SEPARATOR_PATTERN.matcher(date);
        if (m.find()) {
            date = m.replaceFirst("$1-$2-$3");
        }

        // 일련번호 분리: YYYY-MM-DD-NNNNN → YYYY-MM-DD
        Matcher sm = DATE_WITH_SERIAL_PATTERN.matcher(date);
        if (sm.find()) {
            date = sm.group(1);
        }

        return date.trim();
    }

    // ─────────────────────────────────────────────
    //  시간 정규화 (3-2)
    // ─────────────────────────────────────────────

    /**
     * 시간을 HH:MM:SS 형식으로 통일.
     */
    public static String normalizeTime(String time) {
        if (time == null) return null;
        time = time.trim();

        // 한글 시/분 → HH:MM:SS
        Matcher koreanMatcher = TIME_KOREAN_PATTERN.matcher(time);
        if (koreanMatcher.find()) {
            String hour = String.format("%02d", Integer.parseInt(koreanMatcher.group(1)));
            String min = String.format("%02d", Integer.parseInt(koreanMatcher.group(2)));
            return hour + ":" + min + ":00";
        }

        // 1자리 시간 → 0 패딩
        Matcher singleHourMatcher = TIME_SINGLE_HOUR_PATTERN.matcher(time);
        if (singleHourMatcher.matches()) {
            String hour = "0" + singleHourMatcher.group(1);
            String min = singleHourMatcher.group(2);
            String sec = singleHourMatcher.group(3);
            if (sec == null) {
                return hour + ":" + min + ":00";
            }
            return hour + ":" + min + sec;
        }

        // HH:MM (초 없음) → HH:MM:00
        Matcher hhmmMatcher = TIME_HHMM_PATTERN.matcher(time);
        if (hhmmMatcher.matches()) {
            String hour = String.format("%02d", Integer.parseInt(hhmmMatcher.group(1)));
            String min = hhmmMatcher.group(2);
            return hour + ":" + min + ":00";
        }

        // 이미 HH:MM:SS 형식이면 1자리 시간 패딩만
        if (time.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
            String[] parts = time.split(":");
            return String.format("%02d:%s:%s",
                    Integer.parseInt(parts[0]), parts[1], parts[2]);
        }

        return time;
    }

    // ─────────────────────────────────────────────
    //  타임스탬프 정규화
    // ─────────────────────────────────────────────

    private String normalizeTimestamp(String timestamp) {
        if (timestamp == null) return null;

        // 날짜 부분 정규화
        Matcher dateMatcher = DATE_SEPARATOR_PATTERN.matcher(timestamp);
        if (dateMatcher.find()) {
            timestamp = dateMatcher.replaceFirst("$1-$2-$3");
        }

        // 시간 부분이 있으면 정규화
        String[] parts = timestamp.split("\\s+", 2);
        if (parts.length == 2) {
            String datePart = normalizeDate(parts[0]);
            String timePart = normalizeTime(parts[1]);
            return datePart + " " + timePart;
        }

        return timestamp;
    }

    // ─────────────────────────────────────────────
    //  중량 정규화 (3-3)
    // ─────────────────────────────────────────────

    /**
     * 중량 문자열을 정수로 변환.
     */
    public static int normalizeWeight(String raw) {
        if (raw == null) return 0;
        String cleaned = raw.replaceAll("[,\\s]", "");

        // 소수점 처리
        if (cleaned.contains(".")) {
            return (int) Math.round(Double.parseDouble(cleaned));
        }

        return Integer.parseInt(cleaned);
    }

    // ─────────────────────────────────────────────
    //  단위 정규화 (3-4)
    // ─────────────────────────────────────────────

    /**
     * 단위를 소문자 "kg"로 통일.
     */
    public static String normalizeUnit(String unit) {
        if (unit == null) return "kg";
        String trimmed = unit.replaceAll("\\s+", "").trim();
        if (UNIT_NORMALIZE_PATTERN.matcher(trimmed).matches()
                || "kg".equalsIgnoreCase(trimmed)
                || "㎏".equals(trimmed)) {
            return "kg";
        }
        return "kg"; // 기본값
    }

    // ─────────────────────────────────────────────
    //  텍스트 약한 정규화 (3-5, 옵션)
    // ─────────────────────────────────────────────

    /**
     * 텍스트 약한 정규화 (--normalize-text 활성화 시).
     */
    public static String normalizeTextValue(String text) {
        if (text == null) return null;
        // 법인 표기 제거
        text = CORP_PATTERN.matcher(text).replaceAll("");
        // 연속 공백 → 단일 공백
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }
}
