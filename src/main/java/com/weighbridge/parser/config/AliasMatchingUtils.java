package com.weighbridge.parser.config;

import java.util.List;
import java.util.Map;

/**
 * 별칭 매칭 유틸리티.
 *
 * String.contains() 기반으로 완전 일치 / 포함 관계를 판정한다.
 */
public final class AliasMatchingUtils {

    private AliasMatchingUtils() {}

    public static boolean matchesAny(String text, Map<String, List<String>> aliasMap, int threshold) {
        if (text == null || text.isBlank()) return false;
        for (List<String> aliases : aliasMap.values()) {
            for (String alias : aliases) {
                if (score(text, alias) >= threshold) return true;
            }
        }
        return false;
    }

    public static int bestScoreForRole(String label, String role,
                                        Map<String, List<String>> aliasMap) {
        if (label == null || label.isBlank()) return 0;
        List<String> aliases = aliasMap.get(role);
        if (aliases == null) return 0;

        int best = 0;
        for (String alias : aliases) {
            best = Math.max(best, score(label, alias));
        }
        return best;
    }

    public static MatchResult bestMatch(String label, Map<String, List<String>> aliasMap) {
        if (label == null || label.isBlank()) return null;

        String bestField = null;
        int bestScore = 0;

        for (Map.Entry<String, List<String>> entry : aliasMap.entrySet()) {
            for (String alias : entry.getValue()) {
                int s = score(label, alias);
                if (s > bestScore) {
                    bestScore = s;
                    bestField = entry.getKey();
                }
            }
        }
        return bestField != null ? new MatchResult(bestField, bestScore) : null;
    }

    public static int score(String text, String query) {
        if (text == null || query == null) return 0;

        String t = text.trim();
        String q = query.trim();
        if (t.isEmpty() || q.isEmpty()) return 0;

        if (t.equals(q)) {
            return Thresholds.FUZZY_LABEL_CONFIRM;
        }

        if (t.contains(q) || q.contains(t)) {
            return Thresholds.FUZZY_LABEL_WEAK;
        }

        return 0;
    }

    public record MatchResult(String fieldName, int score) {}
}
