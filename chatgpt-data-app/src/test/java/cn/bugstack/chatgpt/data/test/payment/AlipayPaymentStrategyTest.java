package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.model.entity.PaymentResult;
import cn.bugstack.chatgpt.data.infrastructure.payment.AlipayPaymentStrategy;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.domain.AlipayTradeQueryModel;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlipayPaymentStrategyTest {

    private static final String ORDER_ID = "order-1";
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    private AlipayClient client;
    private AlipayPaymentStrategy strategy;

    @Before
    public void setUp() {
        client = mock(AlipayClient.class);
        strategy = new AlipayPaymentStrategy(
                true, "sandbox-app", "private-key", "public-key", "seller-id", "", "http://localhost:3002/payment/return");
        ReflectionTestUtils.setField(strategy, "client", client);
    }

    @Test
    public void checkoutUsesMerchantReturnPageWithoutHash() throws Exception {
        com.alipay.api.response.AlipayTradePagePayResponse response =
                new com.alipay.api.response.AlipayTradePagePayResponse();
        response.setBody("sandbox-checkout");
        when(client.pageExecute(any(com.alipay.api.request.AlipayTradePagePayRequest.class),
                org.mockito.ArgumentMatchers.eq("GET"))).thenReturn(response);

        assertEquals("sandbox-checkout", strategy.create(ORDER_ID, "Wallet", AMOUNT, new Date()));
        org.mockito.ArgumentCaptor<com.alipay.api.request.AlipayTradePagePayRequest> captor =
                forClass(com.alipay.api.request.AlipayTradePagePayRequest.class);
        verify(client).pageExecute(captor.capture(), org.mockito.ArgumentMatchers.eq("GET"));
        assertEquals("http://localhost:3002/payment/return", captor.getValue().getReturnUrl());
        assertEquals(ORDER_ID, ((com.alipay.api.domain.AlipayTradePagePayModel)
                captor.getValue().getBizModel()).getOutTradeNo());
    }

    @Test
    public void paidQueryMapsProviderAmountAndTransaction() throws Exception {
        Date paidAt = new Date();
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo(ORDER_ID);
        response.setTradeNo("provider-tx");
        response.setTotalAmount("10.00");
        response.setSendPayDate(paidAt);
        response.setTradeStatus("TRADE_SUCCESS");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        PaymentResult result = strategy.query(ORDER_ID);

        assertEquals(ORDER_ID, result.getOrderId());
        assertEquals("provider-tx", result.getTransactionId());
        assertEquals(AMOUNT, result.getAmount());
        assertSame(paidAt, result.getPaidAt());
        assertTrue(result.isPaid());
        assertFalse(result.isClosed());

        org.mockito.ArgumentCaptor<AlipayTradeQueryRequest> requestCaptor =
                forClass(AlipayTradeQueryRequest.class);
        verify(client).execute(requestCaptor.capture());
        AlipayTradeQueryRequest request = requestCaptor.getValue();
        assertEquals(ORDER_ID, ((AlipayTradeQueryModel) request.getBizModel()).getOutTradeNo());
    }

    @Test
    public void closedQueryIsNotTreatedAsPaid() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo(ORDER_ID);
        response.setTotalAmount("10.00");
        response.setTradeStatus("TRADE_CLOSED");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        PaymentResult result = strategy.query(ORDER_ID);

        assertFalse(result.isPaid());
        assertTrue(result.isClosed());
        assertEquals(AMOUNT, result.getAmount());
    }

    @Test
    public void providerResponseForAnotherOrderIsRejected() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo("different-order");
        response.setTradeNo("provider-tx");
        response.setTotalAmount("10.00");
        response.setTradeStatus("TRADE_SUCCESS");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertThrows(IllegalArgumentException.class, () -> strategy.query(ORDER_ID));
    }
}
