package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.model.ProcessedLine;

/**
 * Preprocessor 출력 라인에 유형 분류 결과를 부착한 레코드.
 *
 * @param line            원본 ProcessedLine
 * @param type            분류된 라인 유형
 * @param isCandidate     weight_event 약후보 여부 (확정이면 false)
 * @param candidateNote   약후보 승격 시 부가 메시지 (null if 확정)
 */
public record ClassifiedLine(
        ProcessedLine line,
        LineType type,
        boolean isCandidate,
        String candidateNote
) {
    public String text() {
        return line.text();
    }

    public int originalLineIndex() {
        return line.originalLineIndex();
    }
}
