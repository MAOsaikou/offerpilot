package com.offerpilot.career.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HollandMatcherTest {

    @Test
    void identicalAndNormalizedCodesScoreFull() {
        assertThat(HollandMatcher.score("IRC", "IRC")).isEqualTo(6.0);
        assertThat(HollandMatcher.score("AIS", "AIS")).isEqualTo(6.0);
        assertThat(HollandMatcher.score("i r c", "IRC")).isEqualTo(6.0);
        assertThat(HollandMatcher.score("irc", "i-r-c")).isEqualTo(6.0);
    }

    @Test
    void sameLettersInDifferentPositionsAreDiscounted() {
        // I 在第 1 位匹配到职业的第 2 位：3 × 0.5 = 1.5
        // R 在第 2 位匹配到职业的第 1 位：2 × 0.5 = 1.0
        // C 位置一致：1 × 1.0 = 1.0
        assertThat(HollandMatcher.score("IRC", "RIC")).isEqualTo(3.5);
    }

    @Test
    void onlyMatchingLettersScore() {
        // A 位置一致 3 分；I、S 不在职业码里，不得分
        assertThat(HollandMatcher.score("AIS", "ACR")).isEqualTo(3.0);
        // I 错位 1.5 + C 错位 0.5，R 未出现
        assertThat(HollandMatcher.score("IRC", "CIE")).isEqualTo(2.0);
        assertThat(HollandMatcher.score("SSS", "IRC")).isZero();
    }

    @Test
    void emptyOrNullInputsScoreZero() {
        assertThat(HollandMatcher.score(null, "IRC")).isZero();
        assertThat(HollandMatcher.score("IRC", null)).isZero();
        assertThat(HollandMatcher.score("", "IRC")).isZero();
        assertThat(HollandMatcher.score("IRC", "")).isZero();
    }

    @Test
    void shorterCodesAreHandled() {
        // 用户只有两码时，只按前两码计分（3 + 2）
        assertThat(HollandMatcher.score("IR", "IRC")).isEqualTo(5.0);
    }

    @Test
    void topThreePicksHighestDimensions() {
        assertThat(HollandMatcher.topThree(0.44, 0.84, 0.23, 0.14, 0.15, 0.77)).isEqualTo("ICR");
        assertThat(HollandMatcher.topThree(0.04, 0.34, 0.19, 0.42, 0.99, 0.66)).isEqualTo("ECS");
    }

    @Test
    void topThreeIsStableOnTies() {
        // 六维完全相同的时候按 RIASEC 固定顺序取前三位，保证结果可复现
        assertThat(HollandMatcher.topThree(0.5, 0.5, 0.5, 0.5, 0.5, 0.5)).isEqualTo("RIA");
    }
    @Test void profileCorrelationRetainsStrengthsAndIgnoresRatingScaleAndBaseline() {
        var user = new RiasecProfile(2, 2, 0, 1, 1, 0);
        assertThat(user.correlation(new RiasecProfile(7, 7, 3, 5, 5, 3))).isEqualTo(1);
        assertThat(user.correlation(new RiasecProfile(0, 0, 2, 1, 1, 2))).isEqualTo(-1);
        assertThat(user.correlation(new RiasecProfile(1, 1, 1, 1, 1, 1))).isZero();
        assertThat(new RiasecProfile(0, 0, 0, 0, 0, 0).correlation(user)).isZero();
    }
}
