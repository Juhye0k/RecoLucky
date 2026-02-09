package com.weighbridge.parser.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OCR 엔진 결과 JSON의 역직렬화 모델.
 * OCR 결과 JSON을 Java 객체로 변환
 *
 * <p>두 가지 입력 형식을 지원한다:</p>
 * <ol>
 *   <li>{@code lines[]} — 라인 배열 (word별 confidence 활용 가능)</li>
 *   <li>{@code pages[].lines[]} — 페이지 구조 내 라인 배열</li>
 * </ol>
 * <p>{@code lines[]}가 없으면 {@code pages[0].lines[]}를 fallback으로 사용한다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OcrInput {

    @JsonProperty("text")
    private String text;

    @JsonProperty("lines")
    private List<Line> lines;

    @JsonProperty("pages")
    private List<Page> pages;

    public OcrInput() {}

    public OcrInput(String text, List<Line> lines) {
        this.text = text;
        this.lines = lines;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    /**
     * lines를 반환. 직접 lines가 없으면 pages[0].lines에서 추출.
     */
    public List<Line> getLines() {
        if (lines != null && !lines.isEmpty()) {
            return lines;
        }
        if (pages != null && !pages.isEmpty()) {
            return pages.get(0).getLines();
        }
        return lines;
    }

    public void setLines(List<Line> lines) { this.lines = lines; }
    public List<Page> getPages() { return pages; }
    public void setPages(List<Page> pages) { this.pages = pages; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {

        @JsonProperty("lines")
        private List<Line> lines;

        @JsonProperty("text")
        private String text;

        public Page() {}

        public List<Line> getLines() { return lines; }
        public void setLines(List<Line> lines) { this.lines = lines; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Line {

        @JsonProperty("text")
        private String text;

        @JsonProperty("words")
        private List<Word> words;

        public Line() {}

        public Line(String text, List<Word> words) {
            this.text = text;
            this.words = words;
        }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public List<Word> getWords() { return words; }
        public void setWords(List<Word> words) { this.words = words; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Word {

        @JsonProperty("text")
        private String text;

        @JsonProperty("confidence")
        private double confidence;

        public Word() {}

        public Word(String text, double confidence) {
            this.text = text;
            this.confidence = confidence;
        }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }
}
