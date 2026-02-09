package com.weighbridge.parser.config;

/**
 * 파이프라인 전체에서 사용하는 임계값 상수 모음.
 */
public final class Thresholds {

    // ── Preprocessor ──
    public static final double NOISE_DELETE_THRESHOLD = 0.1;
    public static final double NOISE_MARK_THRESHOLD = 0.3;
    public static final double DEFAULT_CONFIDENCE = 1.0;

    // ── OCR confidence WARNING 기준 (통합) ──
    public static final double OCR_CONF_WARNING = 0.7;

    // ── 퍼지 매칭 점수 ──
    public static final int FUZZY_LABEL_CONFIRM = 85;
    public static final int FUZZY_LABEL_WEAK = 50;

    // ── 중량 자릿수 ──
    public static final int WEIGHT_DIGITS_MIN = 3;
    public static final int WEIGHT_DIGITS_MAX = 6;

    // ── assignment_confidence ──
    public static final double ASSIGNMENT_CONF_LABEL = 1.0;
    public static final double ASSIGNMENT_CONF_ARITHMETIC = 0.9;

    // ── 문서 유형 탐색 ──
    public static final int DOC_TYPE_SEARCH_LIMIT = 3;

    private Thresholds() {}
}
