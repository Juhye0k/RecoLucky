package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 중량 필드의 역할 할당 추론 경로.
 *
 * <ul>
 *   <li>{@code LABEL} — 라벨 퍼지 매칭으로 확정 (confidence 1.0)</li>
 *   <li>{@code ARITHMETIC} — 산술 관계(A - B = C)로 확정 (confidence 0.9)</li>
 *   <li>{@code HEURISTIC} — 보조 단서(약매칭, 위치, 시간) 기반 추정 (confidence 0.5)</li>
 * </ul>
 */
public enum InferredBy {
    LABEL,
    ARITHMETIC,
    HEURISTIC;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
