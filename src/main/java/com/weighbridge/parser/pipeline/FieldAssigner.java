package com.weighbridge.parser.pipeline;

import com.weighbridge.parser.config.AliasMatchingUtils;
import com.weighbridge.parser.config.FieldAliases;
import com.weighbridge.parser.config.Thresholds;
import com.weighbridge.parser.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractor Step 2: 필드 할당.
 *
 * ClassifiedLine 목록을 받아 ParsedDocument를 생성한다.
 */
public class FieldAssigner {

    private static final Logger log = LoggerFactory.getLogger(FieldAssigner.class);

    private static final Pattern TIME_PATTERN =
            Pattern.compile("(\\d{1,2}:\\d{2}(:\\d{2})?)");

    private static final Pattern WEIGHT_NUMBER_PATTERN =
            Pattern.compile("(?<![:\\d])(\\d{1,3}[,]\\d{3}|\\d{1,3}\\s\\d{3}|\\d{3,6})(?![:\\d])");

    private static final Pattern KG_UNIT_PATTERN =
            Pattern.compile("(kg)(?![가-힣a-zA-Z])");

    private static final Pattern COLON_SPLIT_PATTERN =
            Pattern.compile("^(.+?)[:：]\\s*(.*)$", Pattern.DOTALL);

    private static final Pattern SERIAL_AFTER_DATE_PATTERN =
            Pattern.compile("\\d{4}[-./]\\d{2}[-./]\\d{2}\\s+(\\d{3,6})");

    private static final Pattern RESIDUAL_AFTER_DATE_PATTERN =
            Pattern.compile("(\\d{4}[-./]\\d{2}[-./]\\d{2})\\s+\\d{1,2}$");

    // ─────────────────────────────────────────────
    //  공개 API
    // ─────────────────────────────────────────────

    public ParsedDocument assign(List<ClassifiedLine> lines, String sourceFile) {
        Map<String, Object> fields = new LinkedHashMap<>();
        List<Map<String, Object>> unassignedWeights = new ArrayList<>();
        List<Map<String, Object>> extraWeights = new ArrayList<>();
        ResolutionHint resolutionHint = null;

        List<WeightCandidate> weightCandidates = parseWeightEvents(lines);
        WeightAssignmentResult weightResult = assignWeightRoles(weightCandidates, lines);

        fields.put("gross_weight", weightResult.grossWeight());
        fields.put("tare_weight", weightResult.tareWeight());
        fields.put("net_weight", weightResult.netWeight());
        unassignedWeights = weightResult.unassignedWeights();
        extraWeights = weightResult.extraWeights();
        resolutionHint = weightResult.resolutionHint();

        processLabelValueLines(lines, fields);
        processOtherLines(lines, fields);

        String documentType = extractDocumentType(lines);

        Map<String, String> inferencePath = weightResult.inferencePath();
        ValidationResult validation = new ValidationResult(
                true, true, null,
                weightResult.rolesUnresolved(),
                inferencePath, null, null
        );

        log.info("필드 할당 완료: 필드={}, document_type={}", fields.keySet(), documentType);
        if (weightResult.rolesUnresolved()) {
            log.warn("중량 역할 UNRESOLVED 발생");
        }

        return new ParsedDocument(
                documentType, sourceFile, fields,
                unassignedWeights, extraWeights,
                resolutionHint, validation
        );
    }

    // ─────────────────────────────────────────────
    //  Step 2-1: weight_event 값 분리
    // ─────────────────────────────────────────────

    public List<WeightCandidate> parseWeightEvents(List<ClassifiedLine> lines) {
        List<WeightCandidate> candidates = new ArrayList<>();
        for (ClassifiedLine cl : lines) {
            if (cl.type() != LineType.WEIGHT_EVENT) continue;

            String text = cl.text();

            String time = null;
            Matcher timeMatcher = TIME_PATTERN.matcher(text);
            if (timeMatcher.find()) {
                time = timeMatcher.group(1);
            }

            Matcher weightMatcher = WEIGHT_NUMBER_PATTERN.matcher(text);
            while (weightMatcher.find()) {
                String rawDigits = weightMatcher.group(1);
                String cleanDigits = rawDigits.replaceAll("[,\\s]", "");
                if (cleanDigits.length() >= Thresholds.WEIGHT_DIGITS_MIN
                        && cleanDigits.length() <= Thresholds.WEIGHT_DIGITS_MAX) {
                    int weight = Integer.parseInt(cleanDigits);

                    String unit = "kg";
                    Matcher unitMatcher = KG_UNIT_PATTERN.matcher(text);
                    if (unitMatcher.find()) {
                        unit = unitMatcher.group(1);
                    }

                    String rawLabel = extractWeightLabel(text);
                    double ocrConf = computeValueTokenConfidence(cl, rawDigits);

                    candidates.add(new WeightCandidate(
                            weight, time, unit, rawDigits, rawLabel,
                            cl.originalLineIndex(), ocrConf, cl
                    ));
                    break;
                }
            }
        }
        return candidates;
    }

    private String extractWeightLabel(String text) {
        String timeStripped = text.replaceAll("\\d{1,2}:\\d{2}(:\\d{2})?", "").trim();
        Matcher colonMatcher = COLON_SPLIT_PATTERN.matcher(timeStripped);
        if (colonMatcher.matches()) {
            return colonMatcher.group(1).trim();
        }
        String beforeTime = text.replaceAll("\\d{1,2}:\\d{2}(:\\d{2})?.*", "").trim();
        return beforeTime.isEmpty() ? null : beforeTime;
    }

    private double computeValueTokenConfidence(ClassifiedLine cl, String rawDigits) {
        List<OcrInput.Word> words = cl.line().words();
        if (words == null || words.isEmpty()) return Thresholds.DEFAULT_CONFIDENCE;

        double minConf = Double.MAX_VALUE;
        boolean found = false;
        for (OcrInput.Word w : words) {
            String wText = w.getText();
            if (wText == null) continue;
            if (wText.contains(rawDigits) || rawDigits.contains(wText.replaceAll("[,\\s]", ""))
                    || KG_UNIT_PATTERN.matcher(wText).find()) {
                minConf = Math.min(minConf, w.getConfidence());
                found = true;
            }
        }
        if (!found) {
            return cl.line().minWordConfidence();
        }
        return minConf;
    }

    // ─────────────────────────────────────────────
    //  역할 할당
    // ─────────────────────────────────────────────

    record WeightAssignmentResult(
            WeightField grossWeight,
            WeightField tareWeight,
            WeightField netWeight,
            List<Map<String, Object>> unassignedWeights,
            List<Map<String, Object>> extraWeights,
            boolean rolesUnresolved,
            ResolutionHint resolutionHint,
            Map<String, String> inferencePath
    ) {}

    WeightAssignmentResult assignWeightRoles(List<WeightCandidate> candidates,
                                                     List<ClassifiedLine> lines) {
        Map<String, String> inferencePath = new LinkedHashMap<>();

        if (candidates.isEmpty()) {
            return new WeightAssignmentResult(
                    WeightField.missing(), WeightField.missing(), WeightField.missing(),
                    List.of(), List.of(), false, null, inferencePath
            );
        }

        List<WeightCandidate> selected;
        List<WeightCandidate> extras = new ArrayList<>();

        if (candidates.size() <= 3) {
            selected = new ArrayList<>(candidates);
        } else {
            TripleSelectionResult tripleResult = selectTriple(candidates);
            selected = tripleResult.selected();
            extras = tripleResult.extras();
            if (tripleResult.method() != null) {
                inferencePath.put("triple_selection", tripleResult.method());
            }
        }

        RoleAssignmentResult roleResult = assignRoles(selected);
        inferencePath.putAll(roleResult.inferencePath());

        List<Map<String, Object>> extraWeightsList = new ArrayList<>();
        for (WeightCandidate ec : extras) {
            extraWeightsList.add(candidateToMap(ec));
        }

        List<Map<String, Object>> unassignedList = new ArrayList<>();
        if (roleResult.rolesUnresolved()) {
            for (WeightCandidate uc : roleResult.unassignedCandidates()) {
                unassignedList.add(candidateToMap(uc));
            }
        }

        return new WeightAssignmentResult(
                roleResult.grossWeight(),
                roleResult.tareWeight(),
                roleResult.netWeight(),
                unassignedList,
                extraWeightsList,
                roleResult.rolesUnresolved(),
                roleResult.resolutionHint(),
                inferencePath
        );
    }

    // ─────────────────────────────────────────────
    //  3중량 세트 선택
    // ─────────────────────────────────────────────

    record TripleSelectionResult(
            List<WeightCandidate> selected,
            List<WeightCandidate> extras,
            String method
    ) {}

    TripleSelectionResult selectTriple(List<WeightCandidate> candidates) {
        List<WeightCandidate> pool = new ArrayList<>(candidates);

        // 산술 탐색: A - B = C (A = gross = 최댓값)
        for (int a = 0; a < pool.size(); a++) {
            for (int b = 0; b < pool.size(); b++) {
                if (b == a) continue;
                for (int c = 0; c < pool.size(); c++) {
                    if (c == a || c == b) continue;
                    int va = pool.get(a).weight();
                    int vb = pool.get(b).weight();
                    int vc = pool.get(c).weight();
                    if (va - vb == vc && va >= vb && va >= vc) {
                        return buildTripleResult(pool, new int[]{a, b, c}, candidates, "arithmetic");
                    }
                }
            }
        }

        // 산술 없으면 라벨 매칭 스코어 상위 3개
        pool.sort((x, y) -> {
            int scoreX = bestOverallWeightScore(x);
            int scoreY = bestOverallWeightScore(y);
            if (scoreX != scoreY) return Integer.compare(scoreY, scoreX);
            return Integer.compare(x.lineIndex(), y.lineIndex());
        });

        List<WeightCandidate> selected = new ArrayList<>(pool.subList(0, Math.min(3, pool.size())));
        Set<WeightCandidate> selectedSet = new LinkedHashSet<>(selected);
        List<WeightCandidate> extras = new ArrayList<>();
        for (WeightCandidate c : candidates) {
            if (!selectedSet.contains(c)) {
                extras.add(c);
            }
        }
        return new TripleSelectionResult(selected, extras, "fallback");
    }

    private TripleSelectionResult buildTripleResult(List<WeightCandidate> pool, int[] indices,
                                                     List<WeightCandidate> allCandidates,
                                                     String method) {
        Set<WeightCandidate> selectedSet = new LinkedHashSet<>();
        for (int idx : indices) {
            selectedSet.add(pool.get(idx));
        }
        List<WeightCandidate> selected = new ArrayList<>(selectedSet);
        List<WeightCandidate> extras = new ArrayList<>();
        for (WeightCandidate c : allCandidates) {
            if (!selectedSet.contains(c)) {
                extras.add(c);
            }
        }
        return new TripleSelectionResult(selected, extras, method);
    }

    private int bestOverallWeightScore(WeightCandidate c) {
        if (c.rawLabel() == null) return 0;
        int best = 0;
        for (String role : List.of("gross_weight", "tare_weight", "net_weight")) {
            best = Math.max(best, bestWeightAliasScore(c.rawLabel(), role));
        }
        return best;
    }

    private int bestWeightAliasScore(String label, String role) {
        return AliasMatchingUtils.bestScoreForRole(label, role, FieldAliases.WEIGHT_ALIASES);
    }

    // ─────────────────────────────────────────────
    //  총/공차/실 역할 할당 (label + arithmetic만)
    // ─────────────────────────────────────────────

    record RoleAssignmentResult(
            WeightField grossWeight,
            WeightField tareWeight,
            WeightField netWeight,
            boolean rolesUnresolved,
            ResolutionHint resolutionHint,
            List<WeightCandidate> unassignedCandidates,
            Map<String, String> inferencePath
    ) {}

    RoleAssignmentResult assignRoles(List<WeightCandidate> selected) {
        Map<String, String> inferencePath = new LinkedHashMap<>();
        Map<String, WeightCandidate> assigned = new LinkedHashMap<>();
        List<WeightCandidate> remaining = new ArrayList<>(selected);

        // 1순위: 라벨 매칭 (≥85점)
        assignByLabel(assigned, remaining, inferencePath);

        // 2순위: 산술 관계 (A - B = C)
        assignByArithmetic(assigned, remaining, inferencePath);

        // heuristic 제거: 라벨/산술로 할당 못 하면 바로 UNRESOLVED
        boolean unresolved = !remaining.isEmpty()
                && (assigned.size() < 3 || !assigned.containsKey("gross_weight")
                    || !assigned.containsKey("tare_weight") || !assigned.containsKey("net_weight"));

        WeightField gross = buildWeightField(assigned.get("gross_weight"),
                inferencePath.get("gross_weight"));
        WeightField tare = buildWeightField(assigned.get("tare_weight"),
                inferencePath.get("tare_weight"));
        WeightField net = buildWeightField(assigned.get("net_weight"),
                inferencePath.get("net_weight"));

        if (unresolved) {
            if (!assigned.containsKey("gross_weight")) gross = WeightField.unresolved("총중량 미확정");
            if (!assigned.containsKey("tare_weight")) tare = WeightField.unresolved("공차중량 미확정");
            if (!assigned.containsKey("net_weight")) net = WeightField.unresolved("실중량 미확정");
        }

        ResolutionHint hint = null;
        if (unresolved && !remaining.isEmpty()) {
            hint = buildResolutionHint(remaining);
        }

        return new RoleAssignmentResult(gross, tare, net, unresolved, hint,
                remaining, inferencePath);
    }

    private void assignByLabel(Map<String, WeightCandidate> assigned,
                                List<WeightCandidate> remaining,
                                Map<String, String> inferencePath) {
        Iterator<WeightCandidate> it = remaining.iterator();
        while (it.hasNext()) {
            WeightCandidate c = it.next();
            if (c.rawLabel() == null) continue;

            String bestRole = null;
            int bestScore = 0;
            for (String role : List.of("gross_weight", "tare_weight", "net_weight")) {
                if (assigned.containsKey(role)) continue;
                int score = bestWeightAliasScore(c.rawLabel(), role);
                if (score >= Thresholds.FUZZY_LABEL_CONFIRM && score > bestScore) {
                    bestScore = score;
                    bestRole = role;
                }
            }
            if (bestRole != null) {
                assigned.put(bestRole, c);
                inferencePath.put(bestRole, "label");
                it.remove();
            }
        }
    }

    private void assignByArithmetic(Map<String, WeightCandidate> assigned,
                                     List<WeightCandidate> remaining,
                                     Map<String, String> inferencePath) {
        if (assigned.size() >= 3) return;

        List<WeightCandidate> all = new ArrayList<>();
        Map<WeightCandidate, String> knownRoles = new HashMap<>();
        for (Map.Entry<String, WeightCandidate> e : assigned.entrySet()) {
            all.add(e.getValue());
            knownRoles.put(e.getValue(), e.getKey());
        }
        all.addAll(remaining);

        for (int a = 0; a < all.size(); a++) {
            for (int b = 0; b < all.size(); b++) {
                if (b == a) continue;
                for (int c = 0; c < all.size(); c++) {
                    if (c == a || c == b) continue;
                    WeightCandidate ca = all.get(a);
                    WeightCandidate cb = all.get(b);
                    WeightCandidate cc = all.get(c);

                    if (ca.weight() - cb.weight() == cc.weight()
                            && ca.weight() >= cb.weight() && ca.weight() >= cc.weight()) {
                        // 기존 할당과 충돌하는지 확인
                        if (!isCompatibleTriple(ca, "gross_weight", cb, "tare_weight",
                                cc, "net_weight", knownRoles)) {
                            continue;
                        }
                        tryAssignArithmetic(assigned, remaining, inferencePath,
                                ca, "gross_weight", knownRoles);
                        tryAssignArithmetic(assigned, remaining, inferencePath,
                                cb, "tare_weight", knownRoles);
                        tryAssignArithmetic(assigned, remaining, inferencePath,
                                cc, "net_weight", knownRoles);
                        return;
                    }
                }
            }
        }
    }

    private boolean isCompatibleTriple(WeightCandidate ca, String roleA,
                                        WeightCandidate cb, String roleB,
                                        WeightCandidate cc, String roleC,
                                        Map<WeightCandidate, String> knownRoles) {
        return isCompatibleRole(ca, roleA, knownRoles)
                && isCompatibleRole(cb, roleB, knownRoles)
                && isCompatibleRole(cc, roleC, knownRoles);
    }

    private boolean isCompatibleRole(WeightCandidate candidate, String role,
                                      Map<WeightCandidate, String> knownRoles) {
        String knownRole = knownRoles.get(candidate);
        return knownRole == null || knownRole.equals(role);
    }

    private void tryAssignArithmetic(Map<String, WeightCandidate> assigned,
                                      List<WeightCandidate> remaining,
                                      Map<String, String> inferencePath,
                                      WeightCandidate candidate, String role,
                                      Map<WeightCandidate, String> knownRoles) {
        if (assigned.containsKey(role)) return;
        String knownRole = knownRoles.get(candidate);
        if (knownRole != null && !knownRole.equals(role)) return;

        assigned.put(role, candidate);
        remaining.remove(candidate);
        if (!inferencePath.containsKey(role)) {
            inferencePath.put(role, "arithmetic");
        }
    }

    private WeightField buildWeightField(WeightCandidate candidate, String inferenceMethod) {
        if (candidate == null) return WeightField.missing();

        InferredBy inferredBy = switch (inferenceMethod) {
            case "label" -> InferredBy.LABEL;
            case "arithmetic" -> InferredBy.ARITHMETIC;
            default -> null;
        };

        double assignConf = switch (inferenceMethod) {
            case "label" -> Thresholds.ASSIGNMENT_CONF_LABEL;
            case "arithmetic" -> Thresholds.ASSIGNMENT_CONF_ARITHMETIC;
            default -> 0.0;
        };

        FieldStatus status = FieldStatus.OK;
        Severity severity = null;
        String message = null;

        if (candidate.ocrConfidence() < Thresholds.OCR_CONF_WARNING) {
            status = FieldStatus.WARNING;
            severity = Severity.LOW;
            message = "OCR 신뢰도 낮음 (" + String.format("%.2f", candidate.ocrConfidence()) + ")";
        }

        return new WeightField(
                candidate.weight(), "kg", candidate.time(),
                candidate.rawLabel(), candidate.rawValue(),
                candidate.ocrConfidence(), assignConf, inferredBy,
                status, severity, message, candidate.lineIndex()
        );
    }

    private ResolutionHint buildResolutionHint(List<WeightCandidate> unassigned) {
        List<Map<String, Object>> candidatesList = new ArrayList<>();
        for (WeightCandidate c : unassigned) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("weight", c.weight());
            info.put("time", c.time());
            info.put("line_index", c.lineIndex());
            info.put("raw_label", c.rawLabel());
            candidatesList.add(info);
        }
        return new ResolutionHint(
                "중량 역할을 자동 결정할 수 없습니다. 수동 확인이 필요합니다.",
                candidatesList
        );
    }

    private Map<String, Object> candidateToMap(WeightCandidate c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("weight", c.weight());
        map.put("time", c.time());
        map.put("unit", "kg");
        map.put("raw_value", c.rawValue());
        map.put("line_index", c.lineIndex());
        map.put("ocr_confidence", c.ocrConfidence());
        return map;
    }

    // ─────────────────────────────────────────────
    //  label_value_line 처리
    // ─────────────────────────────────────────────

    void processLabelValueLines(List<ClassifiedLine> lines, Map<String, Object> fields) {
        for (ClassifiedLine cl : lines) {
            if (cl.type() != LineType.LABEL_VALUE_LINE) continue;

            String text = cl.text();
            Matcher colonMatcher = COLON_SPLIT_PATTERN.matcher(text);
            if (!colonMatcher.matches()) continue;

            String rawLabel = colonMatcher.group(1).trim();
            String rawValue = colonMatcher.group(2).trim();

            Map<String, List<String>> availableAliases = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : FieldAliases.FIELD_ALIASES.entrySet()) {
                if (!fields.containsKey(entry.getKey())) {
                    availableAliases.put(entry.getKey(), entry.getValue());
                }
            }

            AliasMatchingUtils.MatchResult match = AliasMatchingUtils.bestMatch(rawLabel, availableAliases);
            String bestField = match != null ? match.fieldName() : null;
            int bestScore = match != null ? match.score() : 0;

            if (bestField != null && bestScore >= Thresholds.FUZZY_LABEL_WEAK) {
                double ocrConf = computeLabelValueConfidence(cl, rawValue);
                FieldStatus status = FieldStatus.OK;
                Severity severity = null;
                String message = null;

                if (ocrConf < Thresholds.OCR_CONF_WARNING) {
                    status = FieldStatus.WARNING;
                    severity = Severity.LOW;
                    message = "OCR 신뢰도 낮음";
                }

                BaseField field;
                if (status == FieldStatus.WARNING) {
                    field = BaseField.warning(rawValue, rawLabel, rawValue, ocrConf,
                            severity, message, cl.originalLineIndex());
                } else {
                    field = BaseField.ok(rawValue, rawLabel, rawValue, ocrConf,
                            cl.originalLineIndex());
                }
                fields.put(bestField, field);

                if ("measurement_date".equals(bestField)) {
                    Matcher serialMatcher = SERIAL_AFTER_DATE_PATTERN.matcher(rawValue);
                    if (serialMatcher.find()) {
                        String serialNum = serialMatcher.group(1);
                        String dateOnly = rawValue.replaceAll("\\s+" + serialNum, "").trim();
                        if (status == FieldStatus.WARNING) {
                            fields.put("measurement_date", BaseField.warning(dateOnly, rawLabel,
                                    rawValue, ocrConf, severity, message, cl.originalLineIndex()));
                        } else {
                            fields.put("measurement_date", BaseField.ok(dateOnly, rawLabel,
                                    rawValue, ocrConf, cl.originalLineIndex()));
                        }
                        fields.put("serial_number", BaseField.ok(serialNum, rawLabel,
                                rawValue, ocrConf, cl.originalLineIndex()));
                    } else {
                        Matcher residualMatcher = RESIDUAL_AFTER_DATE_PATTERN.matcher(rawValue);
                        if (residualMatcher.find()) {
                            String dateOnly = residualMatcher.group(1);
                            if (status == FieldStatus.WARNING) {
                                fields.put("measurement_date", BaseField.warning(dateOnly, rawLabel,
                                        rawValue, ocrConf, severity, message, cl.originalLineIndex()));
                            } else {
                                fields.put("measurement_date", BaseField.ok(dateOnly, rawLabel,
                                        rawValue, ocrConf, cl.originalLineIndex()));
                            }
                        }
                    }
                }
            }
        }
    }

    private double computeLabelValueConfidence(ClassifiedLine cl, String rawValue) {
        List<OcrInput.Word> words = cl.line().words();
        if (words == null || words.isEmpty()) return Thresholds.DEFAULT_CONFIDENCE;

        double minConf = Double.MAX_VALUE;
        boolean found = false;
        for (OcrInput.Word w : words) {
            if (w.getText() == null) continue;
            String wText = w.getText().replaceAll("[:：]", "").trim();
            if (!wText.isEmpty() && (rawValue.contains(wText) || wText.contains(rawValue))) {
                minConf = Math.min(minConf, w.getConfidence());
                found = true;
            }
        }
        return found ? minConf : cl.line().minWordConfidence();
    }

    // ─────────────────────────────────────────────
    //  기타 라인 처리
    // ─────────────────────────────────────────────

    void processOtherLines(List<ClassifiedLine> lines, Map<String, Object> fields) {
        for (ClassifiedLine cl : lines) {
            switch (cl.type()) {
                case ISSUER_LINE -> {
                    if (!fields.containsKey("issuer")) {
                        double ocrConf = cl.line().minWordConfidence();
                        fields.put("issuer", BaseField.ok(cl.text(), null, cl.text(),
                                ocrConf, cl.originalLineIndex()));
                    }
                }
                case TIMESTAMP_LINE -> {
                    if (!fields.containsKey("issued_at")) {
                        double ocrConf = cl.line().minWordConfidence();
                        fields.put("issued_at", BaseField.ok(cl.text(), null, cl.text(),
                                ocrConf, cl.originalLineIndex()));
                    }
                }
                case GPS_LINE -> {
                    if (!fields.containsKey("gps_coordinates")) {
                        double ocrConf = cl.line().minWordConfidence();
                        fields.put("gps_coordinates", BaseField.ok(cl.text(), null, cl.text(),
                                ocrConf, cl.originalLineIndex()));
                    }
                }
                case DATE_LINE -> processDateLine(cl, fields);
                case ETC_LINE -> processEtcLine(cl, fields);
                default -> { /* 기타 */ }
            }
        }
    }

    private static final Pattern DATE_IN_LINE_PATTERN =
            Pattern.compile("^(.+?)\\s+(\\d{4}[-./]\\d{2}[-./]\\d{2})(.*)$");

    private void processDateLine(ClassifiedLine cl, Map<String, Object> fields) {
        String text = cl.text();
        Matcher m = DATE_IN_LINE_PATTERN.matcher(text);
        if (!m.matches()) return;

        String rawLabel = m.group(1).trim();
        String dateValue = m.group(2).trim();

        Map<String, List<String>> availableAliases = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : FieldAliases.FIELD_ALIASES.entrySet()) {
            if (!fields.containsKey(entry.getKey())) {
                availableAliases.put(entry.getKey(), entry.getValue());
            }
        }

        AliasMatchingUtils.MatchResult match = AliasMatchingUtils.bestMatch(rawLabel, availableAliases);
        if (match != null && match.score() >= Thresholds.FUZZY_LABEL_WEAK) {
            double ocrConf = cl.line().minWordConfidence();
            fields.put(match.fieldName(), BaseField.ok(dateValue, rawLabel, text,
                    ocrConf, cl.originalLineIndex()));
        }
    }

    private static final Pattern NO_DELIMITER_PATTERN =
            Pattern.compile("^(.+?)\\s*No\\.\\s*(.+)$");

    private void processEtcLine(ClassifiedLine cl, Map<String, Object> fields) {
        String text = cl.text();
        Matcher m = NO_DELIMITER_PATTERN.matcher(text);
        if (!m.matches()) return;

        String rawLabel = (m.group(1).trim() + "No").trim();
        String rawValue = m.group(2).trim();

        Map<String, List<String>> availableAliases = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : FieldAliases.FIELD_ALIASES.entrySet()) {
            if (!fields.containsKey(entry.getKey())) {
                availableAliases.put(entry.getKey(), entry.getValue());
            }
        }

        AliasMatchingUtils.MatchResult match = AliasMatchingUtils.bestMatch(rawLabel, availableAliases);
        if (match != null && match.score() >= Thresholds.FUZZY_LABEL_WEAK) {
            double ocrConf = cl.line().minWordConfidence();
            fields.put(match.fieldName(), BaseField.ok(rawValue, rawLabel, text,
                    ocrConf, cl.originalLineIndex()));
        }
    }

    String extractDocumentType(List<ClassifiedLine> lines) {
        int limit = Math.min(Thresholds.DOC_TYPE_SEARCH_LIMIT, lines.size());
        for (int i = 0; i < limit; i++) {
            ClassifiedLine cl = lines.get(i);
            if (cl.type() != LineType.ETC_LINE) continue;

            String text = cl.text();
            for (String alias : FieldAliases.DOC_TYPE_ALIASES) {
                if (text.contains(alias)) {
                    return alias;
                }
            }
        }
        return null;
    }
}
