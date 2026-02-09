package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 필드의 파싱/검증 상태.
 *
 * <ul>
 *   <li>{@code OK} — 정상 추출 및 검증 통과</li>
 *   <li>{@code WARNING} — 추출 성공이나 신뢰도 낮음 또는 검증 주의</li>
 *   <li>{@code ERROR} — 검증 실패 (음수 중량, 산술 불일치 등)</li>
 *   <li>{@code MISSING} — 필드가 문서에 존재하지 않음</li>
 *   <li>{@code UNRESOLVED} — 중량 역할 자동 결정 불가 (수동 확인 필요)</li>
 * </ul>
 */
public enum FieldStatus {
    OK,
    WARNING,
    ERROR,
    MISSING,
    UNRESOLVED;

    @JsonValue
    public String toJson() {
        return name();
    }
}
