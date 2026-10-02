package cn.bugstack.chatgpt.data.domain.account.model.valobj;

import java.math.BigDecimal;

/**
 * Shared validation for externally supplied monetary grants.
 */
public final class BalanceGrantAmount {

    public static final BigDecimal MAX_GRANT_AMOUNT = new BigDecimal("1000000");

    private BalanceGrantAmount() {
    }

    public static void validate(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0
                || amount.scale() > 8
                || amount.compareTo(MAX_GRANT_AMOUNT) > 0) {
            throw new IllegalArgumentException(
                    "balance grant must be positive CNY with at most 8 decimal places and no more than 1000000");
        }
    }
}
