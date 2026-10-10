package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.OrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.OrderStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PaymentEventType;
import cn.bugstack.chatgpt.data.domain.order.service.IOrderService;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import cn.bugstack.chatgpt.data.mq.PaymentEventMessage;
import cn.bugstack.chatgpt.data.mq.PaymentEventRocketMqConsumer;
import com.alibaba.fastjson.JSON;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PaymentEventRocketMqConsumerTest {

    private static final long EVENT_ID = 42L;
    private static final String ORDER_ID = "order-42";

    private IPaymentEventOutboxDao outboxDao;
    private IOrderService orderService;
    private PaymentEventRocketMqConsumer consumer;

    @Before
    public void setUp() {
        outboxDao = mock(IPaymentEventOutboxDao.class);
        orderService = mock(IOrderService.class);
        consumer = new PaymentEventRocketMqConsumer();
        ReflectionTestUtils.setField(consumer, "outboxDao", outboxDao);
        ReflectionTestUtils.setField(consumer, "orderService", orderService);
    }

    @Test
    public void duplicateDoneEventIsAcknowledgedWithoutRepeatingBusinessSideEffect() {
        when(outboxDao.queryById(EVENT_ID)).thenReturn(event(PaymentEventType.DELIVERED,
                PaymentEventOutboxPO.DONE));

        ConsumeConcurrentlyStatus result = consume(PaymentEventType.DELIVERED);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, result);
        verify(orderService, never()).queryOrder(any(String.class));
        verify(outboxDao, never()).markDone(eq(EVENT_ID), any());
    }

    @Test
    public void paidEventThatDoesNotReachCompletedIsRetried() {
        when(outboxDao.queryById(EVENT_ID)).thenReturn(event(PaymentEventType.PAID,
                PaymentEventOutboxPO.SENT));
        when(orderService.queryOrder(ORDER_ID)).thenReturn(order(OrderStatusVO.WAIT));

        ConsumeConcurrentlyStatus result = consume(PaymentEventType.PAID);

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, result);
        verify(orderService).deliverGoods(ORDER_ID);
        verify(outboxDao, never()).markDone(eq(EVENT_ID), any());
        verify(outboxDao).recordConsumeFailure(eq(EVENT_ID), any(), any(String.class));
    }

    @Test
    public void rebateFailureIsRetriedAndNeverMarkedDone() {
        when(outboxDao.queryById(EVENT_ID)).thenReturn(event(PaymentEventType.DELIVERED,
                PaymentEventOutboxPO.SENT));
        when(orderService.queryOrder(ORDER_ID)).thenReturn(order(OrderStatusVO.COMPLETED));
        doThrow(new IllegalStateException("marketing unavailable"))
                .when(orderService).rebateGoods("user-42", ORDER_ID);

        ConsumeConcurrentlyStatus result = consume(PaymentEventType.DELIVERED);

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, result);
        verify(orderService).rebateGoods("user-42", ORDER_ID);
        verify(outboxDao, never()).markDone(eq(EVENT_ID), any());
        verify(outboxDao).recordConsumeFailure(eq(EVENT_ID), any(), any(String.class));
    }

    @Test
    public void payloadOrderMismatchIsRetried() {
        when(outboxDao.queryById(EVENT_ID)).thenReturn(event(PaymentEventType.PAID,
                PaymentEventOutboxPO.SENT));

        MessageExt message = message(new PaymentEventMessage(EVENT_ID, "PAID", "another-order"));
        ConsumeConcurrentlyStatus result = consumer.consumeMessage(
                Collections.singletonList(message), mock(ConsumeConcurrentlyContext.class));

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, result);
        verify(orderService, never()).queryOrder(any(String.class));
        verify(outboxDao).recordConsumeFailure(eq(EVENT_ID), any(), any(String.class));
    }

    private ConsumeConcurrentlyStatus consume(PaymentEventType type) {
        return consumer.consumeMessage(Collections.singletonList(message(
                new PaymentEventMessage(EVENT_ID, type.getCode(), ORDER_ID))),
                mock(ConsumeConcurrentlyContext.class));
    }

    private MessageExt message(PaymentEventMessage payload) {
        MessageExt message = new MessageExt();
        message.setBody(JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private PaymentEventOutboxPO event(PaymentEventType type, int status) {
        return PaymentEventOutboxPO.builder()
                .id(EVENT_ID)
                .eventType(type.getCode())
                .orderId(ORDER_ID)
                .status(status)
                .build();
    }

    private CreateOrderAggregate order(OrderStatusVO status) {
        return CreateOrderAggregate.builder()
                .openid("user-42")
                .order(OrderEntity.builder().orderId(ORDER_ID).orderStatus(status).build())
                .build();
    }
}
