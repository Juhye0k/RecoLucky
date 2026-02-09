package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.config.AliasMatchingUtils;
import com.weighbridge.parser.config.FieldAliases;
import com.weighbridge.parser.config.Thresholds;
import com.weighbridge.parser.model.OcrInput;
import com.weighbridge.parser.model.ProcessedLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 파이프라인 Step 1: OCR 텍스트 전처리.
 *
 * <p>4단계 처리를 수행한다:</p>
 * <ol>
 *   <li>라인 분리 — {@code lines[]} 우선, {@code text} fallback</li>
 *   <li>공백 정규화 — 한글 자모 사이 단일 공백 제거 ({@code 거 래 처} → {@code 거래처})</li>
 *   <li>노이즈 마킹 — 저신뢰 토큰 삭제/마킹 (삭제 아닌 플래그 부여)</li>
 *   <li>라인 병합 — 중량 라벨 + 중량 값 라인 병합 (TEL/FAX 제외)</li>
 * </ol>
 */
public class Preprocessor {

    private static final Logger log = LoggerFactory.getLogger(Preprocessor.class);

    // Korean character single space removal: matches Korean char + single space + Korean char
    private static final Pattern KOREAN_SPACE_PATTERN =
            Pattern.compile("([가-힣])\\s([가-힣])");

    // Phone number pattern
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\d{2,3}-\\d{2,4}-\\d{4}");

    // GPS coordinate pattern
    private static final Pattern GPS_PATTERN =
            Pattern.compile("\\d+\\.\\d+,\\s*\\d+\\.\\d+");

    // Weight value pattern A: time + digits + kg unit
    private static final Pattern WEIGHT_VALUE_PATTERN_A =
            Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?\\s+[\\d,\\s]{3,}\\s*kg");

    // Weight value pattern B: digits + kg unit (no time)
    private static final Pattern WEIGHT_VALUE_PATTERN_B =
            Pattern.compile("[\\d,\\s]{3,}\\s*kg");

    // Non-weight labels that should not trigger merging
    private static final Set<String> NON_WEIGHT_LABELS = Set.of(
            "TEL", "FAX", "tel", "fax", "Tel", "Fax", "전화", "팩스"
    );

    // Label fragment pattern (e.g., "명:" — single char + colon, clearly a broken fragment)
    private static final Pattern LABEL_FRAGMENT_PATTERN =
            Pattern.compile("^.{1}[:：]\\s*$");

    public List<ProcessedLine> process(OcrInput input) {
        // Step 1: 라인 분리
        List<RawLine> rawLines = separateLines(input);
        int inputLineCount = rawLines.size();

        // Step 2: 공백 정규화
        rawLines = normalizeWhitespace(rawLines);

        // Step 3: 노이즈 마킹
        List<ProcessedLine> processedLines = markNoise(rawLines);
        int noiseDeletedCount = inputLineCount - processedLines.size();
        long noiseMarkedCount = processedLines.stream().filter(ProcessedLine::isNoiseCandidate).count();

        // Step 4: 라인 병합
        int beforeMerge = processedLines.size();
        processedLines = mergeLines(processedLines);
        int mergeCount = beforeMerge - processedLines.size();

        log.info("전처리 완료: 입력 {}줄 → 최종 {}줄 (노이즈 삭제 {}, 마킹 {}, 병합 {}건)",
                inputLineCount, processedLines.size(), noiseDeletedCount, noiseMarkedCount, mergeCount);

        return processedLines;
    }

    // 중간 객체
    private record RawLine(String text, List<OcrInput.Word> words, int originalLineIndex) {}

    // ── Step 1: 라인 분리 ──

    private List<RawLine> separateLines(OcrInput input) {
        List<RawLine> result = new ArrayList<>();

        if (input.getLines() != null && !input.getLines().isEmpty()) {
            for (int i = 0; i < input.getLines().size(); i++) {
                OcrInput.Line line = input.getLines().get(i);
                String lineText = buildLineText(line);
                List<OcrInput.Word> words = line.getWords() != null
                        ? line.getWords()
                        : List.of();

                if (!lineText.isBlank()) {
                    result.add(new RawLine(lineText, words, i));
                }
            }
        } else if (input.getText() != null) {
            String[] textLines = input.getText().split("\\n");
            for (int i = 0; i < textLines.length; i++) {
                String line = textLines[i].trim();
                if (!line.isBlank()) {
                    result.add(new RawLine(line, List.of(), i));
                }
            }
        }

        return result;
    }

    private String buildLineText(OcrInput.Line line) {
        if (line.getWords() != null && !line.getWords().isEmpty()) {
            return line.getWords().stream()
                    .map(OcrInput.Word::getText)
                    .collect(Collectors.joining(" "));
        }
        return line.getText() != null ? line.getText() : "";
    }

    // ── Step 2: 공백 정규화 ──

    private List<RawLine> normalizeWhitespace(List<RawLine> lines) {
        List<RawLine> result = new ArrayList<>();
        for (RawLine line : lines) {
            String normalized = removeKoreanSpaces(line.text());
            result.add(new RawLine(normalized, line.words(), line.originalLineIndex()));
        }
        return result;
    }

    public static String removeKoreanSpaces(String text) {
        String result = text;
        String prev;
        do {
            prev = result;
            result = KOREAN_SPACE_PATTERN.matcher(result).replaceAll("$1$2");
        } while (!result.equals(prev));
        return result;
    }

    // ── Step 3: 노이즈 마킹 ──
    // 라인을 삭제할지, 경고 대상으로 표시할지 판단

    private List<ProcessedLine> markNoise(List<RawLine> lines) {
        List<ProcessedLine> result = new ArrayList<>();

        for (RawLine line : lines) {
            double minConf = calculateMinWordConfidence(line.words());

            if (isEntirelyGarbage(line)) {
                continue;
            }

            boolean isNoiseCandidate = !line.words().isEmpty()
                    && minConf < Thresholds.NOISE_MARK_THRESHOLD;

            result.add(new ProcessedLine(
                    line.text(),
                    line.words(),
                    isNoiseCandidate,
                    line.words().isEmpty() ? 1.0 : minConf,
                    line.originalLineIndex()
            ));
        }

        return result;
    }

    private double calculateMinWordConfidence(List<OcrInput.Word> words) {
        if (words == null || words.isEmpty()) return 1.0;
        return words.stream()
                .mapToDouble(OcrInput.Word::getConfidence)
                .min()
                .orElse(1.0);
    }

    private boolean isEntirelyGarbage(RawLine line) {
        if (line.words() == null || line.words().isEmpty()) return false;

        return line.words().stream().allMatch(w ->
                w.getConfidence() < Thresholds.NOISE_DELETE_THRESHOLD
                        && isStandaloneGarbageToken(w.getText())
        );
    }

    private boolean isStandaloneGarbageToken(String text) {
        if (text == null) return true;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return true;
        // Single special characters
        if (trimmed.matches("^[*#@!?.,;:]+$")) return true;
        // Meaningless 1-2 char English
        if (trimmed.matches("^[a-zA-Z]{1,2}$")) return true;
        // Very short meaningless tokens
        return trimmed.length() <= 1;
    }

    // ── Step 4: Line merging ──

    private List<ProcessedLine> mergeLines(List<ProcessedLine> lines) {
        List<ProcessedLine> result = new ArrayList<>();
        // 이미 처리돼서 건너뛰어야하는 인덱스
        Set<Integer> consumed = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            if (consumed.contains(i)) continue;

            ProcessedLine current = lines.get(i);

            if (LABEL_FRAGMENT_PATTERN.matcher(current.text()).matches()) {
                consumed.add(i);
                continue;
            }

            if (isWeightLabelLine(current.text()) && i + 1 < lines.size()) {
                ProcessedLine next = lines.get(i + 1);
                if (!consumed.contains(i + 1) && isWeightValueLine(next.text())) {
                    // Merge
                    String mergedText = current.text() + " " + next.text();
                    List<OcrInput.Word> mergedWords = new ArrayList<>();
                    mergedWords.addAll(current.words());
                    mergedWords.addAll(next.words());
                    double mergedMinConf = Math.min(
                            current.minWordConfidence(), next.minWordConfidence());

                    result.add(new ProcessedLine(
                            mergedText,
                            mergedWords,
                            current.isNoiseCandidate() || next.isNoiseCandidate(),
                            mergedMinConf,
                            current.originalLineIndex()
                    ));
                    consumed.add(i);
                    consumed.add(i + 1);
                    continue;
                }
            }

            result.add(current);
        }

        return result;
    }

    private boolean isWeightLabelLine(String text) {
        String trimmed = text.trim();
        // Must end with colon
        if (!trimmed.endsWith(":") && !trimmed.endsWith("：")) return false;

        // Extract label part (before colon)
        String label = trimmed.replaceAll("[:：]\\s*$", "").trim();

        // Must not contain numeric values (only label text)
        if (label.matches(".*\\d{3,}.*")) return false;

        // Check if label is non-weight (TEL, FAX, etc.)
        for (String nonWeight : NON_WEIGHT_LABELS) {
            if (label.toUpperCase().contains(nonWeight.toUpperCase())) return false;
        }

        // Must have weight alias weak match (>= 50)
        return hasWeightAliasMatch(label);
    }

    private boolean hasWeightAliasMatch(String label) {
        return AliasMatchingUtils.matchesAny(label, FieldAliases.WEIGHT_ALIASES,
                Thresholds.FUZZY_LABEL_WEAK);
    }

    private boolean isWeightValueLine(String text) {
        String trimmed = text.trim();

        // Must not be phone number
        if (PHONE_PATTERN.matcher(trimmed).find()) return false;

        // Must not be GPS coordinates
        if (GPS_PATTERN.matcher(trimmed).find()) return false;

        // Must match weight value pattern (with kg unit)
        if (WEIGHT_VALUE_PATTERN_A.matcher(trimmed).find()) return true;
        if (WEIGHT_VALUE_PATTERN_B.matcher(trimmed).find()) return true;

        return false;
    }
}
