package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.model.InferredBy;

/**
 * weight_event 라인에서 파싱된 중량 후보.
 *
 * @param weight        정수 중량값 (콤마/공백 제거 후)
 * @param time          시간 문자열 (HH:MM:SS 또는 HH:MM), 없으면 null
 * @param unit          단위 원본 (kg, ㎏, KG 등)
 * @param rawValue      원본 중량 문자열 (콤마 포함)
 * @param rawLabel      라인 내 라벨 부분 (있으면)
 * @param lineIndex     원본 라인 인덱스
 * @param ocrConfidence 값 토큰의 최소 confidence
 * @param classifiedLine 원본 ClassifiedLine 참조
 */
public record WeightCandidate(
        int weight,
        String time,
        String unit,
        String rawValue,
        String rawLabel,
        int lineIndex,
        double ocrConfidence,
        ClassifiedLine classifiedLine
) {}
