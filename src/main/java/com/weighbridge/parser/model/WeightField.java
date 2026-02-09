package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 중량 필드(총중량, 공차중량, 실중량)의 파싱 결과.
 *
 * <p>{@link BaseField}의 속성에 더해, 단위({@code unit}), 계량 시각({@code time}),
 * 할당 신뢰도({@code assignmentConfidence}), 추론 경로({@code inferredBy})를 포함한다.</p>
 *
 * @param value                정규화된 중량 값 (정수, kg)
 * @param unit                 단위 (정규화 후 항상 "kg")
 * @param time                 계량 시각 (HH:MM:SS)
 * @param rawLabel             OCR 원문 라벨
 * @param rawValue             OCR 원문 값
 * @param ocrConfidence        OCR 읽기 신뢰도 (0.0~1.0)
 * @param assignmentConfidence 필드 할당 신뢰도 (label=1.0, arithmetic=0.9, heuristic=0.5)
 * @param inferredBy           추론 경로 (LABEL, ARITHMETIC, HEURISTIC)
 * @param status               필드 상태
 * @param severity             WARNING 시 심각도
 * @param message              WARNING/ERROR 메시지
 * @param sourceLineIndex      원본 라인 인덱스
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeightField(
    @JsonProperty("value") Integer value,
    @JsonProperty("unit") String unit,
    @JsonProperty("time") String time,
    @JsonProperty("raw_label") String rawLabel,
    @JsonProperty("raw_value") String rawValue,
    @JsonProperty("ocr_confidence") Double ocrConfidence,
    @JsonProperty("assignment_confidence") Double assignmentConfidence,
    @JsonProperty("inferred_by") InferredBy inferredBy,
    @JsonProperty("status") FieldStatus status,
    @JsonProperty("severity") Severity severity,
    @JsonProperty("message") String message,
    @JsonProperty("source_line_index") Integer sourceLineIndex
) {
    public static WeightField unresolved(String message) {
        return new WeightField(null, null, null, null, null, null, null, null,
                FieldStatus.UNRESOLVED, null, message, null);
    }

    public static WeightField missing() {
        return new WeightField(null, null, null, null, null, null, null, null,
                FieldStatus.MISSING, null, null, null);
    }
}
