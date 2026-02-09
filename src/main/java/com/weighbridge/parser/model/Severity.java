package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * WARNING 상태의 심각도.
 *
 * <ul>
 *   <li>{@code LOW} — OCR 신뢰도 낮음 등 경미한 경고</li>
 *   <li>{@code MEDIUM} — 보조 단서 기반 추정(heuristic) 등 중간 경고</li>
 *   <li>{@code HIGH} — 산술 불일치 등 중대 경고</li>
 * </ul>
 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH;

    @JsonValue
    public String toJson() {
        return name();
    }
}
