package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OrderRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIOrderDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIProductDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIOrderPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PaymentEventType;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

public class OrderRepositoryPaymentTest {

    private static final String ORDER_ID = "order-1";
    private static final String OPEN_ID = "user-1";

    private IOpenAIOrderDao orderDao;
    private IOpenAIProductDao productDao;
    private IUserAccountDao accountDao;
    private IPaymentEventOutboxDao outboxDao;
    private OrderRepository repository;

    @Before
    public void setUp() {
        orderDao = mock(IOpenAIOrderDao.class);
        productDao = mock(IOpenAIProductDao.class);
        accountDao = mock(IUserAccountDao.class);
        outboxDao = mock(IPaymentEventOutboxDao.class);
        repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "openAIOrderDao", orderDao);
        ReflectionTestUtils.setField(repository, "openAIProductDao", productDao);
        ReflectionTestUtils.setField(repository, "userAccountDao", accountDao);
        ReflectionTestUtils.setField(repository, "paymentEventOutboxDao", outboxDao);
        ReflectionTestUtils.setField(repository, "modelTypes", "sandbox-model");
    }

    @Test
    public void historicalNullCreditOrderStaysPendingForManualHandling() throws Exception {
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(order());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1);

        repository.deliverGoods(ORDER_ID);

        verify(orderDao, never()).updateOrderStatusDeliverGoods(ORDER_ID);
        verify(accountDao, never()).queryUserAccount(any(String.class));
        verify(accountDao, never()).insert(any(UserAccountPO.class));

        Method method = OrderRepository.class.getMethod("deliverGoods", String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertArrayEquals(new Class<?>[]{Exception.class}, transactional.rollbackFor());
    }

    @Test
    public void paymentSuccessReturnsDaoCasOutcome() {
        when(orderDao.changeOrderPaySuccess(any(OpenAIOrderPO.class))).thenReturn(0, 1);

        boolean first = repository.changeOrderPaySuccess(
                ORDER_ID, "provider-tx", new java.math.BigDecimal("10.00"), new java.util.Date());
        boolean second = repository.changeOrderPaySuccess(
                ORDER_ID, "provider-tx", new java.math.BigDecimal("10.00"), new java.util.Date());

        assertEquals(false, first);
        assertEquals(true, second);
        verify(orderDao, times(2)).changeOrderPaySuccess(any(OpenAIOrderPO.class));
    }

    @Test
    public void paymentSuccessWritesPaidEventOnlyForSuccessfulCas() {
        when(orderDao.changeOrderPaySuccess(any(OpenAIOrderPO.class))).thenReturn(1);

        assertEquals(true, repository.changeOrderPaySuccess(
                ORDER_ID, "provider-tx", new java.math.BigDecimal("10.00"), new java.util.Date()));

        ArgumentCaptor<PaymentEventOutboxPO> eventCaptor = ArgumentCaptor.forClass(PaymentEventOutboxPO.class);
        verify(outboxDao).insertIgnore(eventCaptor.capture());
        assertEquals(PaymentEventType.PAID.getCode(), eventCaptor.getValue().getEventType());
    }

    private OpenAIOrderPO order() {
        return OpenAIOrderPO.builder()
                .orderId(ORDER_ID)
                .openid(OPEN_ID)
                .productCreditAmount(null)
                .build();
    }

}
