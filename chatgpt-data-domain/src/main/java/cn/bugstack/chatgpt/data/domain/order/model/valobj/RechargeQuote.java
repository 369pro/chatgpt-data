package cn.bugstack.chatgpt.data.domain.order.model.valobj;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Immutable quote for a tiered recharge handling fee.
 */
public final class RechargeQuote {

    private static final BigDecimal FEE_THRESHOLD = new BigDecimal("50.00");
    private static final BigDecimal LOW_TIER_FEE_RATE = new BigDecimal("0.20");
    private static final BigDecimal HIGH_TIER_FEE_RATE = new BigDecimal("0.10");

    private final BigDecimal feeRate;
    private final BigDecimal feeAmount;
    private final BigDecimal creditAmount;

    private RechargeQuote(BigDecimal feeRate, BigDecimal feeAmount, BigDecimal creditAmount) {
        this.feeRate = feeRate;
        this.feeAmount = feeAmount;
        this.creditAmount = creditAmount;
    }

    public static RechargeQuote from(BigDecimal grossAmount) {
        if (grossAmount == null || grossAmount.signum() <= 0) {
            throw new IllegalArgumentException("Gross amount must be positive");
        }

        final BigDecimal normalizedGross;
        try {
            normalizedGross = grossAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Gross amount must have at most two decimal places", e);
        }

        BigDecimal feeRate = normalizedGross.compareTo(FEE_THRESHOLD) <= 0
                ? LOW_TIER_FEE_RATE
                : HIGH_TIER_FEE_RATE;
        BigDecimal feeAmount = normalizedGross.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal creditAmount = normalizedGross.subtract(feeAmount).setScale(2, RoundingMode.UNNECESSARY);
        return new RechargeQuote(feeRate, feeAmount, creditAmount);
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }
}
