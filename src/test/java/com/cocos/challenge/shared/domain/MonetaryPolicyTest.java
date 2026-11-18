package com.cocos.challenge.shared.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonetaryPolicyTest {

    @ParameterizedTest(name = "{0} -> {1} = {2}%")
    @CsvSource({
            "1520.25, 1502.80, -1.15",   // BMA in the seed
            "100,     110,     10.00",
            "100,     100,     0.00",
            "3,       4,       33.33",   // 33.333... rounded down
            "3,       5,       66.67",   // 66.666... rounded up
            "1000,    1000.125, 0.01",   // 0.0125 -> HALF_EVEN keeps the even digit
            "1000,    1000.135, 0.01"    // 0.0135 -> HALF_EVEN rounds to even (0.01), HALF_UP would give 0.02
    })
    void percentChangeIsRoundedToTwoDecimalsHalfEven(BigDecimal from, BigDecimal to, BigDecimal expected) {
        BigDecimal change = MonetaryPolicy.percentChange(from, to);

        assertThat(change).isEqualByComparingTo(expected);
        assertThat(change.scale()).isEqualTo(2);
    }

    @Test
    void percentChangeIsUndefinedWithoutBaseOrValue() {
        assertThat(MonetaryPolicy.percentChange(BigDecimal.ZERO, BigDecimal.TEN)).isNull();
        assertThat(MonetaryPolicy.percentChange(null, BigDecimal.TEN)).isNull();
        assertThat(MonetaryPolicy.percentChange(BigDecimal.TEN, null)).isNull();
    }

    @ParameterizedTest(name = "{0} / {1} = {2} shares")
    @CsvSource({
            "10000,   925.85, 10",   // 10.80 -> 10
            "925.85,  925.85, 1",
            "925.84,  925.85, 0",
            "1000000, 0.01,   100000000"
    })
    void sharesForAnAmountAreTruncated(BigDecimal amount, BigDecimal price, BigDecimal expected) {
        assertThat(MonetaryPolicy.sharesFor(amount, price)).isEqualByComparingTo(expected);
    }

    @Test
    void sharesForRequiresAPositivePrice() {
        assertThatThrownBy(() -> MonetaryPolicy.sharesFor(BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyPresentationUsesTwoDecimals() {
        assertThat(MonetaryPolicy.toMoneyScale(new BigDecimal("930.125"))).isEqualTo(new BigDecimal("930.12"));
        assertThat(MonetaryPolicy.toMoneyScale(null)).isNull();
    }
}
