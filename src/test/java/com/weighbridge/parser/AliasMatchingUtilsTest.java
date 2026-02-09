package com.weighbridge.parser;

import com.weighbridge.parser.config.AliasMatchingUtils;
import com.weighbridge.parser.config.FieldAliases;
import com.weighbridge.parser.config.Thresholds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AliasMatchingUtilsTest {

    @Test
    @DisplayName("동일 문자열 → CONFIRM(85)")
    void exactMatch() {
        assertThat(AliasMatchingUtils.score("총중량", "총중량"))
                .isEqualTo(Thresholds.FUZZY_LABEL_CONFIRM);
        assertThat(AliasMatchingUtils.score("공차중량", "공차중량"))
                .isEqualTo(Thresholds.FUZZY_LABEL_CONFIRM);
    }

    @Test
    @DisplayName("포함 관계 → WEAK(50)")
    void containsMatch() {
        assertThat(AliasMatchingUtils.score("총중량:", "총중량"))
                .isGreaterThanOrEqualTo(Thresholds.FUZZY_LABEL_WEAK);
        assertThat(AliasMatchingUtils.score("중량", "총중량"))
                .isGreaterThanOrEqualTo(Thresholds.FUZZY_LABEL_WEAK);
    }

    @Test
    @DisplayName("무관한 문자열 → 0")
    void noMatch() {
        assertThat(AliasMatchingUtils.score("abc", "xyz")).isEqualTo(0);
    }

    @Test
    @DisplayName("null/빈 문자열 → 0 또는 false")
    void nullAndEmpty() {
        assertThat(AliasMatchingUtils.score(null, "test")).isEqualTo(0);
        assertThat(AliasMatchingUtils.score("test", null)).isEqualTo(0);
        assertThat(AliasMatchingUtils.matchesAny(null,
                FieldAliases.WEIGHT_ALIASES, Thresholds.FUZZY_LABEL_WEAK)).isFalse();
        assertThat(AliasMatchingUtils.matchesAny("",
                FieldAliases.WEIGHT_ALIASES, Thresholds.FUZZY_LABEL_WEAK)).isFalse();
    }

    @Test
    @DisplayName("matchesAny: 별칭 사전 매칭")
    void matchesAny() {
        assertThat(AliasMatchingUtils.matchesAny("총중량",
                FieldAliases.WEIGHT_ALIASES, Thresholds.FUZZY_LABEL_WEAK)).isTrue();
        assertThat(AliasMatchingUtils.matchesAny("거래처",
                FieldAliases.WEIGHT_ALIASES, Thresholds.FUZZY_LABEL_WEAK)).isFalse();
    }

    @Test
    @DisplayName("bestMatch: 필드 매칭")
    void bestMatch() {
        AliasMatchingUtils.MatchResult result =
                AliasMatchingUtils.bestMatch("계량일자", FieldAliases.FIELD_ALIASES);
        assertThat(result).isNotNull();
        assertThat(result.fieldName()).isEqualTo("measurement_date");

        assertThat(AliasMatchingUtils.bestMatch("xyz", Map.of("test", List.of("abc"))))
                .isNull();
    }

    @Test
    @DisplayName("bestScoreForRole: 역할별 점수")
    void bestScoreForRole() {
        assertThat(AliasMatchingUtils.bestScoreForRole("총중량", "gross_weight",
                FieldAliases.WEIGHT_ALIASES))
                .isGreaterThanOrEqualTo(Thresholds.FUZZY_LABEL_WEAK);
        assertThat(AliasMatchingUtils.bestScoreForRole("거래처", "gross_weight",
                FieldAliases.WEIGHT_ALIASES))
                .isEqualTo(0);
    }
}
