package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 중량 역할 미확정(UNRESOLVED) 시 수동 확인을 위한 힌트.
 *
 * <p>할당되지 못한 중량 후보들의 정보(값, 시간, 라벨 스코어 등)를 제공하여
 * 사용자가 수동으로 역할을 결정할 수 있도록 돕는다.</p>
 *
 * @param description 사람이 읽을 수 있는 설명 메시지
 * @param candidates  미할당 중량 후보 목록 (각 후보의 weight, time, label_scores 포함)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolutionHint(
    @JsonProperty("description") String description,
    @JsonProperty("candidates") List<Map<String, Object>> candidates
) {}
