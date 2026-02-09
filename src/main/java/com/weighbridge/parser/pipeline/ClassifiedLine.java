package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.model.ProcessedLine;

/**
 * Preprocessor 출력 라인에 유형 분류 결과를 부착한 레코드.
 *
 * @param line            원본 ProcessedLine
 * @param type            분류된 라인 유형
 */
public record ClassifiedLine(
        ProcessedLine line,
        LineType type
) {
    public String text() {
        return line.text();
    }

    public int originalLineIndex() {
        return line.originalLineIndex();
    }
}
