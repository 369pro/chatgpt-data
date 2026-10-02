package cn.bugstack.chatgpt.data.domain.order.model.entity;

import lombok.Value;
import java.math.BigDecimal;
import java.util.Date;

@Value
public class PaymentResult {
    String orderId;
    String transactionId;
    BigDecimal amount;
    Date paidAt;
    boolean paid;
    boolean closed;
}
