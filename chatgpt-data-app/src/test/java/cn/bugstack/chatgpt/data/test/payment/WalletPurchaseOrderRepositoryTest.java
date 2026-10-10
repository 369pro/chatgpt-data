package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.OrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ProductEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ShopCartEntity;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.OrderStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OrderRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountWalletDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IBalanceGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIOrderDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIProductDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountWalletPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.BalanceGrantPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIOrderPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIProductPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

public class WalletPurchaseOrderRepositoryTest {

    private static final String ORDER_ID = "PAYMENT0001";
    private static final String OPEN_ID = "user-1";
    private static final BigDecimal GROSS_AMOUNT = new BigDecimal("9.90");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.20");
    private static final BigDecimal FEE_AMOUNT = new BigDecimal("1.98");
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("7.92");

    private IOpenAIOrderDao orderDao;
    private IOpenAIProductDao productDao;
    private IUserAccountDao accountDao;
    private IAccountWalletDao walletDao;
    private IBalanceGrantDao grantDao;
    private IPaymentEventOutboxDao outboxDao;
    private OrderRepository repository;

    @Before
    public void setUp() {
        orderDao = mock(IOpenAIOrderDao.class);
        productDao = mock(IOpenAIProductDao.class);
        accountDao = mock(IUserAccountDao.class);
        walletDao = mock(IAccountWalletDao.class);
        grantDao = mock(IBalanceGrantDao.class);
        outboxDao = mock(IPaymentEventOutboxDao.class);
        repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "openAIOrderDao", orderDao);
        ReflectionTestUtils.setField(repository, "openAIProductDao", productDao);
        ReflectionTestUtils.setField(repository, "userAccountDao", accountDao);
        ReflectionTestUtils.setField(repository, "accountWalletDao", walletDao);
        ReflectionTestUtils.setField(repository, "balanceGrantDao", grantDao);
        ReflectionTestUtils.setField(repository, "paymentEventOutboxDao", outboxDao);
        ReflectionTestUtils.setField(repository, "modelTypes", "sandbox-model");
    }

    @Test
    public void walletDeliveryCreatesGrantAndWallet() {
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(walletOrder());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1);
        when(accountDao.queryUserAccount(OPEN_ID)).thenReturn(existingAccount());
        when(grantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(1);
        when(walletDao.addOrCreateWallet(any(AccountWalletPO.class))).thenReturn(1);

        repository.deliverGoods(ORDER_ID);

        ArgumentCaptor<BalanceGrantPO> grantCaptor = ArgumentCaptor.forClass(BalanceGrantPO.class);
        verify(grantDao).insertIgnore(grantCaptor.capture());
        assertEquals("payment", grantCaptor.getValue().getAppId());
        assertEquals(ORDER_ID, grantCaptor.getValue().getRequestId());
        assertEquals(OPEN_ID, grantCaptor.getValue().getOpenid());
        assertEquals(0, CREDIT_AMOUNT.compareTo(grantCaptor.getValue().getAmount()));
        verify(walletDao).addOrCreateWallet(any(AccountWalletPO.class));
        verify(accountDao, never()).insert(any(UserAccountPO.class));
        verify(outboxDao).insertIgnore(any(PaymentEventOutboxPO.class));
    }

    @Test
    public void walletDeliveryInitializesAccountWhenMissing() {
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(walletOrder());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1);
        when(accountDao.queryUserAccount(OPEN_ID)).thenReturn(null);
        when(grantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(1);
        when(walletDao.addOrCreateWallet(any(AccountWalletPO.class))).thenReturn(1);

        repository.deliverGoods(ORDER_ID);

        ArgumentCaptor<UserAccountPO> accountCaptor = ArgumentCaptor.forClass(UserAccountPO.class);
        verify(accountDao).insert(accountCaptor.capture());
    }

    @Test
    public void duplicateGrantMustMatchOrderPayload() {
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(walletOrder());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1);
        when(accountDao.queryUserAccount(OPEN_ID)).thenReturn(existingAccount());
        when(grantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(0);
        when(grantDao.query("payment", ORDER_ID)).thenReturn(BalanceGrantPO.builder()
                .openid(OPEN_ID)
                .amount(new BigDecimal("8.90"))
                .build());

        assertThrows(IllegalStateException.class, () -> repository.deliverGoods(ORDER_ID));

        verify(walletDao, never()).addOrCreateWallet(any(AccountWalletPO.class));
    }

    @Test
    public void historicalNullCreditSnapshotStaysPending() {
        OpenAIOrderPO historical = OpenAIOrderPO.builder()
                .orderId(ORDER_ID)
                .openid(OPEN_ID)
                .productCreditAmount(null)
                .build();
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(historical);

        repository.deliverGoods(ORDER_ID);

        verify(orderDao, never()).updateOrderStatusDeliverGoods(ORDER_ID);
        verify(accountDao, never()).queryUserAccount(any(String.class));
        verify(accountDao, never()).insert(any(UserAccountPO.class));
        verify(grantDao, never()).insertIgnore(any(BalanceGrantPO.class));
        verify(walletDao, never()).addOrCreateWallet(any(AccountWalletPO.class));
    }

    @Test
    public void historicalNullFeeSnapshotKeepsOriginalWalletCredit() {
        OpenAIOrderPO historical = walletOrder();
        historical.setProductCreditAmount(GROSS_AMOUNT);
        historical.setProductFeeRate(null);
        historical.setProductFeeAmount(null);
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(historical);
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1);
        when(accountDao.queryUserAccount(OPEN_ID)).thenReturn(existingAccount());
        when(grantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(1);
        when(walletDao.addOrCreateWallet(any(AccountWalletPO.class))).thenReturn(1);

        repository.deliverGoods(ORDER_ID);

        ArgumentCaptor<BalanceGrantPO> grantCaptor = ArgumentCaptor.forClass(BalanceGrantPO.class);
        verify(grantDao).insertIgnore(grantCaptor.capture());
        assertEquals(0, GROSS_AMOUNT.compareTo(grantCaptor.getValue().getAmount()));
        verify(walletDao).addOrCreateWallet(any(AccountWalletPO.class));
    }

    @Test
    public void deliveryUsesRequiredTransactionAndCasStopsRetries() throws Exception {
        Method method = OrderRepository.class.getMethod("deliverGoods", String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRED, transactional.propagation());

        when(orderDao.queryOrder(ORDER_ID)).thenReturn(walletOrder());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(0);

        repository.deliverGoods(ORDER_ID);

        verify(accountDao, never()).queryUserAccount(any(String.class));
        verify(grantDao, never()).insertIgnore(any(BalanceGrantPO.class));
        verify(walletDao, never()).addOrCreateWallet(any(AccountWalletPO.class));
    }

    @Test
    public void repeatedDeliveryUsesOneWalletGrantAndOneOutboxEvent() {
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(walletOrder());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1, 0);
        when(accountDao.queryUserAccount(OPEN_ID)).thenReturn(existingAccount());
        when(grantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(1);
        when(walletDao.addOrCreateWallet(any(AccountWalletPO.class))).thenReturn(1);

        repository.deliverGoods(ORDER_ID);
        repository.deliverGoods(ORDER_ID);

        verify(grantDao).insertIgnore(any(BalanceGrantPO.class));
        verify(walletDao).addOrCreateWallet(any(AccountWalletPO.class));
        verify(outboxDao).insertIgnore(any(PaymentEventOutboxPO.class));
    }

    @Test
    public void productAndOrderQueriesCarryRechargeQuoteSnapshot() {
        when(productDao.queryProductByProductId(1001)).thenReturn(OpenAIProductPO.builder()
                .productId(1001)
                .productName("wallet")
                .productDesc("CNY")
                .price(GROSS_AMOUNT)
                .creditAmount(CREDIT_AMOUNT)
                .isEnabled(1)
                .build());
        ProductEntity product = repository.queryProduct(1001);
        assertEquals(0, GROSS_AMOUNT.compareTo(product.getPrice()));
        assertEquals(0, CREDIT_AMOUNT.compareTo(product.getCreditAmount()));
        assertEquals(0, FEE_RATE.compareTo(product.getFeeRate()));
        assertEquals(0, FEE_AMOUNT.compareTo(product.getFeeAmount()));

        when(orderDao.queryOrder(ORDER_ID)).thenReturn(walletOrder());
        CreateOrderAggregate order = repository.queryOrder(ORDER_ID);
        assertEquals(0, CREDIT_AMOUNT.compareTo(order.getProduct().getCreditAmount()));
        assertEquals(0, FEE_RATE.compareTo(order.getProduct().getFeeRate()));
        assertEquals(0, FEE_AMOUNT.compareTo(order.getProduct().getFeeAmount()));

        when(productDao.queryProductList()).thenReturn(java.util.Collections.singletonList(
                OpenAIProductPO.builder().productId(1001).price(GROSS_AMOUNT)
                        .creditAmount(CREDIT_AMOUNT).isEnabled(1).build()));
        ProductEntity listedProduct = repository.queryProductList().get(0);
        assertEquals(0, CREDIT_AMOUNT.compareTo(listedProduct.getCreditAmount()));
        assertEquals(0, FEE_RATE.compareTo(listedProduct.getFeeRate()));
        assertEquals(0, FEE_AMOUNT.compareTo(listedProduct.getFeeAmount()));
    }

    @Test
    public void newOrderSnapshotsWalletRechargeQuote() {
        ProductEntity product = ProductEntity.builder()
                .productId(1001)
                .productName("wallet")
                .price(GROSS_AMOUNT)
                .creditAmount(GROSS_AMOUNT)
                .build();
        product.applyRechargeFee();
        CreateOrderAggregate aggregate = CreateOrderAggregate.builder()
                .openid(OPEN_ID)
                .product(product)
                .order(OrderEntity.builder()
                        .orderId(ORDER_ID)
                        .orderTime(new java.util.Date())
                        .orderStatus(OrderStatusVO.CREATE)
                        .totalAmount(GROSS_AMOUNT)
                        .payTypeVO(PayTypeVO.ALIPAY_SANDBOX)
                        .build())
                .build();

        repository.saveOrder(aggregate);

        ArgumentCaptor<OpenAIOrderPO> orderCaptor = ArgumentCaptor.forClass(OpenAIOrderPO.class);
        verify(orderDao).insert(orderCaptor.capture());
        assertEquals(0, CREDIT_AMOUNT.compareTo(orderCaptor.getValue().getProductCreditAmount()));
        assertEquals(0, GROSS_AMOUNT.compareTo(orderCaptor.getValue().getTotalAmount()));
        assertEquals(0, FEE_RATE.compareTo(orderCaptor.getValue().getProductFeeRate()));
        assertEquals(0, FEE_AMOUNT.compareTo(orderCaptor.getValue().getProductFeeAmount()));
    }

    @Test
    public void unpaidLookupUsesCurrentProductCreditAndPrice() {
        when(productDao.queryProductByProductId(1001)).thenReturn(OpenAIProductPO.builder()
                .productId(1001)
                .price(GROSS_AMOUNT)
                .creditAmount(CREDIT_AMOUNT)
                .isEnabled(1)
                .build());
        when(orderDao.queryUnpaidOrder(any(OpenAIOrderPO.class))).thenReturn(null);

        repository.queryUnpaidOrder(ShopCartEntity.builder()
                .openid(OPEN_ID)
                .productId(1001)
                .payType(PayTypeVO.ALIPAY_SANDBOX)
                .build());

        ArgumentCaptor<OpenAIOrderPO> requestCaptor = ArgumentCaptor.forClass(OpenAIOrderPO.class);
        verify(orderDao).queryUnpaidOrder(requestCaptor.capture());
        assertEquals(0, CREDIT_AMOUNT.compareTo(requestCaptor.getValue().getProductCreditAmount()));
        assertEquals(0, GROSS_AMOUNT.compareTo(requestCaptor.getValue().getTotalAmount()));
        assertEquals(0, FEE_RATE.compareTo(requestCaptor.getValue().getProductFeeRate()));
        assertEquals(0, FEE_AMOUNT.compareTo(requestCaptor.getValue().getProductFeeAmount()));
    }

    private OpenAIOrderPO walletOrder() {
        return OpenAIOrderPO.builder()
                .orderId(ORDER_ID)
                .openid(OPEN_ID)
                .productCreditAmount(CREDIT_AMOUNT)
                .productFeeRate(FEE_RATE)
                .productFeeAmount(FEE_AMOUNT)
                .orderTime(new java.util.Date())
                .orderStatus(1)
                .totalAmount(GROSS_AMOUNT)
                .payType(PayTypeVO.ALIPAY_SANDBOX.getCode())
                .build();
    }

    private UserAccountPO existingAccount() {
        return UserAccountPO.builder().openid(OPEN_ID).build();
    }
}
