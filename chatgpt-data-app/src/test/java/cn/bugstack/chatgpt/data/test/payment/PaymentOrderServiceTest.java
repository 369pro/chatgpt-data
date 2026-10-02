package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.adapter.port.PaymentStrategy;
import cn.bugstack.chatgpt.data.domain.order.adapter.repository.IOrderRepository;
import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.OrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.PaymentResult;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ProductEntity;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.OrderStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.domain.order.service.OrderService;
import cn.bugstack.chatgpt.data.domain.order.service.PaymentStrategies;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PaymentOrderServiceTest {

    private static final String ORDER_ID = "order-1";
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("10.00");

    private IOrderRepository orderRepository;
    private PaymentStrategy paymentStrategy;
    private OrderService orderService;

    @Before
    public void setUp() {
        orderRepository = mock(IOrderRepository.class);
        paymentStrategy = mock(PaymentStrategy.class);
        PaymentStrategies paymentStrategies = mock(PaymentStrategies.class);
        when(paymentStrategies.get(PayTypeVO.ALIPAY_SANDBOX)).thenReturn(paymentStrategy);

        orderService = new OrderService();
        ReflectionTestUtils.setField(orderService, "orderRepository", orderRepository);
        ReflectionTestUtils.setField(orderService, "paymentStrategies", paymentStrategies);
    }

    @Test
    public void paidQueryWithMismatchedAmountIsRejectedBeforeDelivery() {
        when(orderRepository.queryOrder(ORDER_ID)).thenReturn(order(OrderStatusVO.CREATE));
        when(paymentStrategy.query(ORDER_ID)).thenReturn(
                new PaymentResult(ORDER_ID, "provider-tx", new BigDecimal("9.99"),
                        new Date(), true, false));

        assertThrows(IllegalArgumentException.class, () -> orderService.reconcileOrder(ORDER_ID));

        verify(orderRepository, never()).changeOrderPaySuccess(
                eq(ORDER_ID), anyString(), any(BigDecimal.class), any(Date.class));
        verify(orderRepository, never()).deliverGoods(ORDER_ID);
    }

    @Test
    public void signedPaidNotificationUsesTrustedQueryTransaction() {
        Map<String, String> parameters = Collections.singletonMap("sign", "verified-by-provider");
        Date paidAt = new Date();
        when(orderRepository.queryOrder(ORDER_ID)).thenReturn(order(OrderStatusVO.CREATE));
        when(paymentStrategy.verifyNotification(parameters)).thenReturn(
                new PaymentResult(ORDER_ID, "callback-tx", ORDER_AMOUNT, paidAt, true, false));
        when(paymentStrategy.query(ORDER_ID)).thenReturn(
                new PaymentResult(ORDER_ID, "trusted-query-tx", ORDER_AMOUNT, paidAt, true, false));

        orderService.acceptNotification(PayTypeVO.ALIPAY_SANDBOX, parameters);

        verify(paymentStrategy).verifyNotification(parameters);
        verify(paymentStrategy).query(ORDER_ID);
        verify(orderRepository).changeOrderPaySuccess(
                ORDER_ID, "trusted-query-tx", ORDER_AMOUNT, paidAt);
        verify(orderRepository).deliverGoods(ORDER_ID);
    }

    @Test
    public void paidQueryMarksPaidAndDelivers() {
        Date paidAt = new Date();
        when(orderRepository.queryOrder(ORDER_ID)).thenReturn(order(OrderStatusVO.CREATE));
        when(paymentStrategy.query(ORDER_ID)).thenReturn(
                new PaymentResult(ORDER_ID, "provider-tx", ORDER_AMOUNT, paidAt, true, false));

        orderService.reconcileOrder(ORDER_ID);

        verify(orderRepository).changeOrderPaySuccess(ORDER_ID, "provider-tx", ORDER_AMOUNT, paidAt);
        verify(orderRepository).deliverGoods(ORDER_ID);
        verify(orderRepository, never()).changeOrderClose(ORDER_ID);
    }

    @Test
    public void closedQueryClosesWithoutDelivery() {
        when(orderRepository.queryOrder(ORDER_ID)).thenReturn(order(OrderStatusVO.CREATE));
        when(paymentStrategy.query(ORDER_ID)).thenReturn(
                new PaymentResult(ORDER_ID, null, ORDER_AMOUNT, null, false, true));

        orderService.reconcileOrder(ORDER_ID);

        verify(orderRepository).changeOrderClose(ORDER_ID);
        verify(orderRepository, never()).changeOrderPaySuccess(
                anyString(), anyString(), any(BigDecimal.class), any(Date.class));
        verify(orderRepository, never()).deliverGoods(ORDER_ID);
    }

    private CreateOrderAggregate order(OrderStatusVO status) {
        return CreateOrderAggregate.builder()
                .openid("user-1")
                .product(ProductEntity.builder().productId(1001).productName("sandbox-plan").quota(100).build())
                .order(OrderEntity.builder()
                        .orderId(ORDER_ID)
                        .orderTime(new Date())
                        .orderStatus(status)
                        .totalAmount(ORDER_AMOUNT)
                        .payTypeVO(PayTypeVO.ALIPAY_SANDBOX)
                        .build())
                .build();
    }
}
