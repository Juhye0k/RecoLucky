package com.weighbridge.parser.model;

import java.util.List;

/**
 * Preprocessor의 출력: 전처리된 라인.
 *
 * @param text              정규화된 텍스트 (한글 자모 공백 제거, 라인 병합 적용 후)
 * @param words             원본 word 목록 (confidence 정보 보존)
 * @param isNoiseCandidate  노이즈 후보 여부 (min word confidence &lt; 0.3)
 * @param minWordConfidence 라인 내 최저 word confidence
 * @param originalLineIndex 원본 OCR 라인 인덱스
 */
public record ProcessedLine(
    String text,
    List<OcrInput.Word> words,
    boolean isNoiseCandidate,
    double minWordConfidence,
    int originalLineIndex
) {}
