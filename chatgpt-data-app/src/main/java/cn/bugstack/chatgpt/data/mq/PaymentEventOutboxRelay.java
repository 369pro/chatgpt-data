package cn.bugstack.chatgpt.data.mq;

import cn.bugstack.chatgpt.data.config.PaymentEventRocketMqProperties;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Relays database events with synchronous RocketMQ sends. A row becomes sent
 * only after producer.send returns successfully. Sent rows remain eligible
 * after the confirmation window until the consumer marks them done.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.events.rocketmq", name = "enabled", havingValue = "true")
public class PaymentEventOutboxRelay {

    private static final String TAG = "payment-lifecycle";
    private static final long INITIAL_BACKOFF_MS = 5_000L;
    private static final long MAX_BACKOFF_MS = 5 * 60_000L;

    @Resource
    private IPaymentEventOutboxDao outboxDao;
    @Resource
    private DefaultMQProducer producer;
    @Resource
    private PaymentEventRocketMqProperties properties;

    @Scheduled(fixedDelayString = "${payment.events.rocketmq.scan-fixed-delay-ms:5000}")
    public void relay() {
        Date now = new Date();
        List<PaymentEventOutboxPO> events = outboxDao.queryReadyForSend(now, properties.getBatchSize());
        for (PaymentEventOutboxPO event : events) {
            sendOne(event, now);
        }
    }

    private void sendOne(PaymentEventOutboxPO event, Date now) {
        try {
            PaymentEventMessage payload = new PaymentEventMessage(
                    event.getId(), event.getEventType(), event.getOrderId());
            Message message = new Message(
                    properties.getTopic(), TAG,
                    JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8));
            message.setKeys(event.getEventType() + ":" + event.getOrderId());

            // Synchronous confirmation is required before marking the row sent.
            SendResult result = producer.send(message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ send was not acknowledged");
            }
            Date nextAttemptAt = new Date(now.getTime()
                    + properties.getConfirmationWindowSeconds() * 1000L);
            outboxDao.markSent(event.getId(), now, nextAttemptAt);
        } catch (Exception e) {
            Date nextAttemptAt = new Date(now.getTime() + backoffMillis(event.getSendAttempts()));
            try {
                outboxDao.recordSendFailure(event.getId(), nextAttemptAt, safeError(e));
            } catch (Exception recordFailure) {
                // Keep the original send failure visible and let the next
                // scan retry the still-pending row.
                log.warn("Unable to record payment event send failure, eventId={}",
                        event.getId(), recordFailure);
            }
            log.warn("Payment event send failed, eventId={}, eventType={}, orderId={}",
                    event.getId(), event.getEventType(), event.getOrderId(), e);
        }
    }

    private long backoffMillis(Integer attempts) {
        int count = attempts == null ? 0 : Math.max(0, Math.min(attempts, 6));
        return Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << count);
    }

    static String safeError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() <= 480 ? message : message.substring(0, 480);
    }
}
