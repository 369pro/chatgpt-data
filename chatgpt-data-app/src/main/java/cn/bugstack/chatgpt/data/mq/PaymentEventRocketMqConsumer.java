package cn.bugstack.chatgpt.data.mq;

import cn.bugstack.chatgpt.data.config.PaymentEventRocketMqProperties;
import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.OrderStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PaymentEventType;
import cn.bugstack.chatgpt.data.domain.order.service.IOrderService;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Handles payment lifecycle messages. A business exception is surfaced as
 * {@link ConsumeConcurrentlyStatus#RECONSUME_LATER}; it is never converted to
 * a successful acknowledgement.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.events.rocketmq", name = "enabled", havingValue = "true")
public class PaymentEventRocketMqConsumer implements MessageListenerConcurrently {

    private static final long MIN_CONSUME_BACKOFF_MS = 60_000L;
    private static final long MAX_CONSUME_BACKOFF_MS = 5 * 60_000L;

    @Resource
    private IPaymentEventOutboxDao outboxDao;
    @Resource
    private IOrderService orderService;
    @Resource
    private PaymentEventRocketMqProperties properties;

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                     ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            PaymentEventMessage payload = null;
            try {
                payload = parse(message);
                process(payload);
            } catch (Exception e) {
                if (payload != null && payload.getId() != null) {
                    try {
                        outboxDao.recordConsumeFailure(payload.getId(),
                                new Date(System.currentTimeMillis() + consumeBackoffMillis(payload.getId())),
                                PaymentEventOutboxRelay.safeError(e));
                    } catch (Exception recordFailure) {
                        log.warn("Unable to record payment event consume failure, eventId={}",
                                payload.getId(), recordFailure);
                    }
                }
                log.warn("Payment event consume failed and will be retried, eventId={}",
                        payload == null ? null : payload.getId(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private PaymentEventMessage parse(MessageExt message) {
        if (message == null || message.getBody() == null) {
            throw new IllegalArgumentException("Payment event payload is empty");
        }
        PaymentEventMessage payload = JSON.parseObject(
                new String(message.getBody(), StandardCharsets.UTF_8), PaymentEventMessage.class);
        if (payload == null || payload.getId() == null
                || payload.getEventType() == null || payload.getOrderId() == null) {
            throw new IllegalArgumentException("Payment event payload is incomplete");
        }
        return payload;
    }

    private void process(PaymentEventMessage payload) {
        PaymentEventOutboxPO event = outboxDao.queryById(payload.getId());
        if (event == null) {
            throw new IllegalStateException("Payment event does not exist: " + payload.getId());
        }
        if (!payload.getEventType().equals(event.getEventType())
                || !payload.getOrderId().equals(event.getOrderId())) {
            throw new IllegalArgumentException("Payment event payload does not match outbox");
        }
        if (Integer.valueOf(PaymentEventOutboxPO.DONE).equals(event.getStatus())) {
            return;
        }

        PaymentEventType eventType = PaymentEventType.fromCode(event.getEventType());
        CreateOrderAggregate aggregate = orderService.queryOrder(event.getOrderId());
        if (aggregate == null || aggregate.getOrder() == null) {
            throw new IllegalStateException("Payment event order does not exist: " + event.getOrderId());
        }

        if (eventType == PaymentEventType.PAID) {
            OrderStatusVO status = aggregate.getOrder().getOrderStatus();
            if (status != OrderStatusVO.WAIT && status != OrderStatusVO.COMPLETED) {
                throw new IllegalStateException("PAID event order is not paid: " + event.getOrderId());
            }
            orderService.deliverGoods(event.getOrderId());
            CreateOrderAggregate delivered = orderService.queryOrder(event.getOrderId());
            if (delivered == null || delivered.getOrder() == null
                    || delivered.getOrder().getOrderStatus() != OrderStatusVO.COMPLETED) {
                throw new IllegalStateException("PAID event delivery did not complete: " + event.getOrderId());
            }
        } else if (eventType == PaymentEventType.DELIVERED) {
            if (aggregate.getOrder().getOrderStatus() != OrderStatusVO.COMPLETED) {
                throw new IllegalStateException("DELIVERED event order is not delivered: " + event.getOrderId());
            }
            // The user ID is loaded from the current order aggregate, never
            // trusted from the MQ payload.
            orderService.rebateGoods(aggregate.getOpenid(), event.getOrderId());
        }

        int marked = outboxDao.markDone(event.getId(), new Date());
        if (marked == 0) {
            PaymentEventOutboxPO latest = outboxDao.queryById(event.getId());
            if (latest == null || !Integer.valueOf(PaymentEventOutboxPO.DONE).equals(latest.getStatus())) {
                throw new IllegalStateException("Payment event completion was lost: " + event.getId());
            }
        }
    }

    private long consumeBackoffMillis(Long eventId) {
        // Keep the relay behind the broker's retry window. Otherwise a failed
        // consumer retry and the outbox relay can publish duplicate messages
        // at the same time. The delay grows to five minutes as failures rise.
        PaymentEventOutboxPO event = outboxDao.queryById(eventId);
        int attempts = event == null || event.getConsumeAttempts() == null
                ? 0 : Math.max(0, Math.min(event.getConsumeAttempts(), 3));
        long configuredWindow = properties == null
                ? 0L : properties.getConfirmationWindowSeconds() * 1000L;
        long base = Math.max(MIN_CONSUME_BACKOFF_MS, configuredWindow);
        return Math.min(MAX_CONSUME_BACKOFF_MS, base << attempts);
    }
}
