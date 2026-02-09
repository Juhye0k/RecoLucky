package com.weighbridge.parser.pipeline;

/**
 * Extractor가 분류한 라인 유형.
 *
 * <p>Value-First 원칙에 따라 값 패턴으로 결정된다:</p>
 * <ul>
 *   <li>{@code WEIGHT_EVENT} — kg 단위 + 3~6자리 정수 (중량 라인)</li>
 *   <li>{@code LABEL_VALUE_LINE} — 콜론 구분 라벨:값 쌍</li>
 *   <li>{@code TIMESTAMP_LINE} — YYYY-MM-DD HH:MM:SS (발행 시각)</li>
 *   <li>{@code GPS_LINE} — 소수점 좌표 (GPS)</li>
 *   <li>{@code DATE_LINE} — YYYY-MM-DD 단독 (날짜)</li>
 *   <li>{@code ISSUER_LINE} — (주)/C&S 등 발행사</li>
 *   <li>{@code ETC_LINE} — 위에 해당하지 않는 기타 라인</li>
 * </ul>
 */
public enum LineType {
    WEIGHT_EVENT,
    LABEL_VALUE_LINE,
    TIMESTAMP_LINE,
    GPS_LINE,
    DATE_LINE,
    ISSUER_LINE,
    ETC_LINE
}
