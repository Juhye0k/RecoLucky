package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 교차 검증 결과.
 *
 * <p>문서 레벨의 일관성({@code isConsistent})과 자동 처리 가능 여부({@code isActionable})를
 * 분리하여 후속 시스템이 명확하게 판단할 수 있도록 한다.</p>
 *
 * @param isConsistent          내부 일관성 (산술, 날짜 유효성, 필수 필드)
 * @param isActionable          자동 처리 가능 (일관성 + 필수 필드 OK + UNRESOLVED 없음)
 * @param weightArithmetic      중량 산술 검증 결과 ({@code passed}, {@code delta} 등)
 * @param weightRolesUnresolved 중량 역할 미확정 여부
 * @param weightInferencePath   각 중량 필드의 추론 경로 (label/arithmetic/heuristic)
 * @param requiredFields        필수 필드 존재 여부
 * @param fieldWarnings         필드별 WARNING 메시지 목록
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationResult(
    @JsonProperty("is_consistent") boolean isConsistent,
    @JsonProperty("is_actionable") boolean isActionable,
    @JsonProperty("weight_arithmetic") Map<String, Object> weightArithmetic,
    @JsonProperty("weight_roles_unresolved") boolean weightRolesUnresolved,
    @JsonProperty("weight_inference_path") Map<String, String> weightInferencePath,
    @JsonProperty("required_fields") Map<String, Object> requiredFields,
    @JsonProperty("field_warnings") List<String> fieldWarnings
) {}
