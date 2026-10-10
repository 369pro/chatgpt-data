package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.config.PaymentEventRocketMqProperties;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import cn.bugstack.chatgpt.data.mq.PaymentEventOutboxRelay;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PaymentEventOutboxRelayTest {

    private IPaymentEventOutboxDao outboxDao;
    private DefaultMQProducer producer;
    private PaymentEventRocketMqProperties properties;
    private PaymentEventOutboxRelay relay;

    @Before
    public void setUp() {
        outboxDao = mock(IPaymentEventOutboxDao.class);
        producer = mock(DefaultMQProducer.class);
        properties = new PaymentEventRocketMqProperties();
        properties.setTopic("payment_lifecycle");
        properties.setBatchSize(10);
        properties.setConfirmationWindowSeconds(60L);
        relay = new PaymentEventOutboxRelay();
        ReflectionTestUtils.setField(relay, "outboxDao", outboxDao);
        ReflectionTestUtils.setField(relay, "producer", producer);
        ReflectionTestUtils.setField(relay, "properties", properties);
    }

    @Test
    public void successfulSynchronousSendMarksEventSent() throws Exception {
        PaymentEventOutboxPO event = event(PaymentEventOutboxPO.PENDING);
        when(outboxDao.queryReadyForSend(any(Date.class), eq(10)))
                .thenReturn(Collections.singletonList(event));
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(producer.send(any(Message.class))).thenReturn(sendResult);

        relay.relay();

        verify(producer).send(any(Message.class));
        verify(outboxDao).markSent(eq(7L), any(Date.class), any(Date.class));
        verify(outboxDao, never()).recordSendFailure(any(), any(Date.class), any(String.class));
    }

    @Test
    public void failedSendKeepsEventRetryableAndDoesNotMarkSent() throws Exception {
        PaymentEventOutboxPO event = event(PaymentEventOutboxPO.PENDING);
        when(outboxDao.queryReadyForSend(any(Date.class), eq(10)))
                .thenReturn(Collections.singletonList(event));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(producer).send(any(Message.class));

        relay.relay();

        verify(producer).send(any(Message.class));
        verify(outboxDao, never()).markSent(any(), any(Date.class), any(Date.class));
        verify(outboxDao).recordSendFailure(eq(7L), any(Date.class), any(String.class));
    }

    @Test
    public void nonOkSendStatusRemainsRetryable() throws Exception {
        PaymentEventOutboxPO event = event(PaymentEventOutboxPO.SENT);
        when(outboxDao.queryReadyForSend(any(Date.class), eq(10)))
                .thenReturn(Collections.singletonList(event));
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(producer.send(any(Message.class))).thenReturn(sendResult);

        relay.relay();

        verify(outboxDao, never()).markSent(any(), any(Date.class), any(Date.class));
        verify(outboxDao).recordSendFailure(eq(7L), any(Date.class), any(String.class));
    }

    private PaymentEventOutboxPO event(int status) {
        return PaymentEventOutboxPO.builder()
                .id(7L)
                .eventType("DELIVERED")
                .orderId("order-7")
                .status(status)
                .sendAttempts(1)
                .build();
    }
}
