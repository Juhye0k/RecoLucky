package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 일반 필드(날짜, 차량번호, 거래처 등)의 파싱 결과.
 *
 * <p>OCR 원본 정보({@code rawLabel}, {@code rawValue})와 정규화된 값({@code value}),
 * 신뢰도({@code ocrConfidence}), 상태({@code status})를 함께 보존한다.</p>
 *
 * @param value           정규화된 필드 값
 * @param rawLabel        OCR 원문 라벨
 * @param rawValue        OCR 원문 값
 * @param ocrConfidence   OCR 읽기 신뢰도 (0.0~1.0)
 * @param status          필드 상태 (OK, WARNING, ERROR, MISSING)
 * @param severity        WARNING 시 심각도
 * @param message         WARNING/ERROR 메시지
 * @param sourceLineIndex 원본 라인 인덱스
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaseField(
    @JsonProperty("value") String value,
    @JsonProperty("raw_label") String rawLabel,
    @JsonProperty("raw_value") String rawValue,
    @JsonProperty("ocr_confidence") Double ocrConfidence,
    @JsonProperty("status") FieldStatus status,
    @JsonProperty("severity") Severity severity,
    @JsonProperty("message") String message,
    @JsonProperty("source_line_index") Integer sourceLineIndex
) {
    public static BaseField missing() {
        return new BaseField(null, null, null, null, FieldStatus.MISSING, null, null, null);
    }

    public static BaseField ok(String value, String rawLabel, String rawValue,
                               Double ocrConfidence, Integer sourceLineIndex) {
        return new BaseField(value, rawLabel, rawValue, ocrConfidence, FieldStatus.OK, null, null, sourceLineIndex);
    }

    public static BaseField warning(String value, String rawLabel, String rawValue,
                                    Double ocrConfidence, Severity severity, String message,
                                    Integer sourceLineIndex) {
        return new BaseField(value, rawLabel, rawValue, ocrConfidence, FieldStatus.WARNING,
                severity, message, sourceLineIndex);
    }
}
