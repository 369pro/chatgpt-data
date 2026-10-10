package cn.bugstack.chatgpt.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Native RocketMQ settings for payment lifecycle events.
 *
 * <p>The feature is disabled by default so unit tests and local development
 * do not require a broker. Production enables it explicitly through the
 * profile or {@code PAYMENT_EVENTS_ROCKETMQ_ENABLED}.</p>
 */
@Data
@ConfigurationProperties(prefix = "payment.events.rocketmq")
public class PaymentEventRocketMqProperties {

    private boolean enabled;
    private String namesrvAddr = "rocketmq-namesrv:9876";
    private String topic = "payment_lifecycle";
    private String producerGroup = "chatgpt-payment-outbox";
    private String consumerGroup = "chatgpt-payment-lifecycle";
    private long confirmationWindowSeconds = 60L;
    private long scanFixedDelayMs = 5000L;
    private int batchSize = 100;
}
