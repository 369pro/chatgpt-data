package cn.bugstack.chatgpt.data.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MQ payload. User, amount and credentials are deliberately absent; the
 * consumer loads the durable outbox row and current order from the database.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEventMessage {

    private Long id;
    private String eventType;
    private String orderId;
}
