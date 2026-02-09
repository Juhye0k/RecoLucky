package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 파싱 파이프라인의 최종 출력 모델.
 *
 * <p>하나의 계량증명서 OCR 결과를 파싱한 결과를 담는다.
 * {@link com.weighbridge.parser.output.JsonWriter}를 통해 직렬화된다.</p>
 *
 * @param documentType      문서 유형 (계량증명서, 계근표 등)
 * @param sourceFile        원본 파일명
 * @param fields            추출된 필드 맵 (BaseField 또는 WeightField)
 * @param unassignedWeights 역할 미할당 중량 목록 (UNRESOLVED 시)
 * @param extraWeights      3중량 세트 선택 후 잉여 중량 목록
 * @param resolutionHint    UNRESOLVED 시 수동 확인 힌트
 * @param validation        교차 검증 결과
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedDocument(
    @JsonProperty("document_type") String documentType,
    @JsonProperty("source_file") String sourceFile,
    @JsonProperty("fields") Map<String, Object> fields,
    @JsonProperty("unassigned_weights") List<Map<String, Object>> unassignedWeights,
    @JsonProperty("extra_weights") List<Map<String, Object>> extraWeights,
    @JsonProperty("resolution_hint") ResolutionHint resolutionHint,
    @JsonProperty("validation") ValidationResult validation
) {}
