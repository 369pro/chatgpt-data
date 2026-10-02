package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.model.valobj.RechargeQuote;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RechargeQuoteTest {

    @Test
    public void calculatesTieredFeeAndCreditAmount() {
        assertQuote("9.90", "0.20", "1.98", "7.92");
        assertQuote("29.90", "0.20", "5.98", "23.92");
        assertQuote("49.99", "0.20", "10.00", "39.99");
        assertQuote("50.00", "0.20", "10.00", "40.00");
        assertQuote("50.01", "0.10", "5.00", "45.01");
        assertQuote("99.90", "0.10", "9.99", "89.91");
        assertQuote("100.00", "0.10", "10.00", "90.00");
    }

    @Test
    public void rejectsInvalidGrossAmounts() {
        assertThrows(IllegalArgumentException.class, () -> RechargeQuote.from(null));
        assertThrows(IllegalArgumentException.class, () -> RechargeQuote.from(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> RechargeQuote.from(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> RechargeQuote.from(new BigDecimal("9.999")));
    }

    private void assertQuote(String gross, String feeRate, String feeAmount, String creditAmount) {
        RechargeQuote quote = RechargeQuote.from(new BigDecimal(gross));

        assertEquals(new BigDecimal(feeRate), quote.getFeeRate());
        assertEquals(new BigDecimal(feeAmount), quote.getFeeAmount());
        assertEquals(new BigDecimal(creditAmount), quote.getCreditAmount());
    }
}
