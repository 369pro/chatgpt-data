package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.adapter.port.PaymentStrategy;
import cn.bugstack.chatgpt.data.domain.order.adapter.repository.IOrderRepository;
import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.OrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.PayOrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ProductEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ShopCartEntity;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.OrderStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.domain.order.service.OrderService;
import cn.bugstack.chatgpt.data.domain.order.service.PaymentStrategies;
import cn.bugstack.chatgpt.data.types.enums.OpenAIProductEnableModel;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WalletPurchaseValidationTest {

    private static final BigDecimal GROSS_AMOUNT = new BigDecimal("9.90");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.20");
    private static final BigDecimal FEE_AMOUNT = new BigDecimal("1.98");
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("7.92");

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
    public void invalidWalletCreditIsRejectedBeforeOrderPersistence() {
        when(orderRepository.queryProduct(1001)).thenReturn(product(BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(cart()));

        verify(orderRepository, never()).queryUnpaidOrder(org.mockito.ArgumentMatchers.any());
        verify(orderRepository, never()).saveOrder(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void historicalNullCreditProductIsRejectedBeforeOrderPersistence() {
        when(orderRepository.queryProduct(1001)).thenReturn(product(null));

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(cart()));

        verify(orderRepository, never()).queryUnpaidOrder(org.mockito.ArgumentMatchers.any());
        verify(orderRepository, never()).saveOrder(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void createOrderAppliesRechargeFeeBeforePersistence() {
        ProductEntity product = product(GROSS_AMOUNT);
        when(orderRepository.queryProduct(1001)).thenReturn(product);
        when(orderRepository.queryUnpaidOrder(any(ShopCartEntity.class))).thenReturn(null);
        when(orderRepository.queryOrder(anyString())).thenAnswer(invocation -> CreateOrderAggregate.builder()
                .openid("user-1")
                .product(product)
                .order(OrderEntity.builder()
                        .orderId(invocation.getArgument(0))
                        .orderTime(new Date())
                        .orderStatus(OrderStatusVO.CREATE)
                        .totalAmount(GROSS_AMOUNT)
                        .payTypeVO(PayTypeVO.ALIPAY_SANDBOX)
                        .build())
                .build());
        when(paymentStrategy.create(anyString(), eq("wallet"), eq(GROSS_AMOUNT), any(Date.class)))
                .thenReturn("pay-url");

        PayOrderEntity payment = orderService.createOrder(cart());

        assertEquals("pay-url", payment.getPayUrl());
        ArgumentCaptor<CreateOrderAggregate> aggregateCaptor = ArgumentCaptor.forClass(CreateOrderAggregate.class);
        verify(orderRepository).saveOrder(aggregateCaptor.capture());
        ProductEntity savedProduct = aggregateCaptor.getValue().getProduct();
        assertEquals(0, GROSS_AMOUNT.compareTo(aggregateCaptor.getValue().getOrder().getTotalAmount()));
        assertEquals(0, CREDIT_AMOUNT.compareTo(savedProduct.getCreditAmount()));
        assertEquals(0, FEE_RATE.compareTo(savedProduct.getFeeRate()));
        assertEquals(0, FEE_AMOUNT.compareTo(savedProduct.getFeeAmount()));
    }

    @Test
    public void missingPriceIsRejectedBeforeOrderPersistence() {
        ProductEntity product = product(new BigDecimal("9.90"));
        product.setPrice(null);
        when(orderRepository.queryProduct(1001)).thenReturn(product);

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(cart()));

        verify(orderRepository, never()).queryUnpaidOrder(org.mockito.ArgumentMatchers.any());
        verify(orderRepository, never()).saveOrder(org.mockito.ArgumentMatchers.any());
    }

    private ProductEntity product(BigDecimal creditAmount) {
        return ProductEntity.builder()
                .productId(1001)
                .productName("wallet")
                .productDesc("CNY")
                .price(new BigDecimal("9.90"))
                .creditAmount(creditAmount)
                .enable(OpenAIProductEnableModel.OPEN)
                .build();
    }

    private ShopCartEntity cart() {
        return ShopCartEntity.builder()
                .openid("user-1")
                .productId(1001)
                .payType(PayTypeVO.ALIPAY_SANDBOX)
                .build();
    }
}
