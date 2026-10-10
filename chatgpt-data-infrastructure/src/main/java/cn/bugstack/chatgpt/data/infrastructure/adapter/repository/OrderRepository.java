package cn.bugstack.chatgpt.data.infrastructure.adapter.repository;

import cn.bugstack.chatgpt.data.domain.openai.model.valobj.UserAccountStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.*;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.OrderStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayStatusVO;
import cn.bugstack.chatgpt.data.domain.order.adapter.repository.IOrderRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIOrderDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIProductDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountWalletDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IBalanceGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountWalletPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.BalanceGrantPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIOrderPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIProductPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PaymentEventType;
import cn.bugstack.chatgpt.data.types.enums.OpenAIProductEnableModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 订单仓储服务
 * @create 2023-10-05 13:25
 */
@Repository
public class OrderRepository implements IOrderRepository {

    private static final String PAYMENT_APP_ID = "payment";
    private static final String WALLET_GRANT_SOURCE = "wallet_purchase";

    @Value("${app.config.model-types:deepseek-flash,deepseek-v4-pro,glm-5.3-flash}")
    private String modelTypes;
    @Resource
    private IOpenAIOrderDao openAIOrderDao;
    @Resource
    private IOpenAIProductDao openAIProductDao;
    @Resource
    private IUserAccountDao userAccountDao;
    @Resource
    private IAccountWalletDao accountWalletDao;
    @Resource
    private IBalanceGrantDao balanceGrantDao;
    @Resource
    private IPaymentEventOutboxDao paymentEventOutboxDao;

    @Override
    public UnpaidOrderEntity queryUnpaidOrder(ShopCartEntity shopCartEntity) {
        OpenAIOrderPO openAIOrderPOReq = new OpenAIOrderPO();
        openAIOrderPOReq.setOpenid(shopCartEntity.getOpenid());
        openAIOrderPOReq.setProductId(shopCartEntity.getProductId());
        openAIOrderPOReq.setPayType(shopCartEntity.getPayType().getCode());
        openAIOrderPOReq.setOrderTime(new Date(System.currentTimeMillis() - 30 * 60_000L));
        ProductEntity currentProduct = queryProduct(shopCartEntity.getProductId());
        if (currentProduct == null) return null;
        openAIOrderPOReq.setProductCreditAmount(currentProduct.getCreditAmount());
        openAIOrderPOReq.setProductFeeRate(currentProduct.getFeeRate());
        openAIOrderPOReq.setProductFeeAmount(currentProduct.getFeeAmount());
        openAIOrderPOReq.setTotalAmount(currentProduct.getPrice());
        OpenAIOrderPO openAIOrderPORes = openAIOrderDao.queryUnpaidOrder(openAIOrderPOReq);
        if (null == openAIOrderPORes) return null;
        return UnpaidOrderEntity.builder()
                .openid(shopCartEntity.getOpenid())
                .orderId(openAIOrderPORes.getOrderId())
                .productName(openAIOrderPORes.getProductName())
                .totalAmount(openAIOrderPORes.getTotalAmount())
                .payUrl(openAIOrderPORes.getPayUrl())
                .payStatus(PayStatusVO.get(openAIOrderPORes.getPayStatus()))
                .build();
    }

    @Override
    public ProductEntity queryProduct(Integer productId) {
        OpenAIProductPO openAIProductPO = openAIProductDao.queryProductByProductId(productId);
        if (openAIProductPO == null) return null;
        ProductEntity productEntity = new ProductEntity();
        productEntity.setProductId(openAIProductPO.getProductId());
        productEntity.setProductName(openAIProductPO.getProductName());
        productEntity.setProductDesc(openAIProductPO.getProductDesc());
        productEntity.setPrice(openAIProductPO.getPrice());
        productEntity.setCreditAmount(openAIProductPO.getCreditAmount());
        productEntity.setEnable(OpenAIProductEnableModel.get(openAIProductPO.getIsEnabled()));
        productEntity.applyRechargeFee();
        return productEntity;
    }

    @Override
    public void saveOrder(CreateOrderAggregate aggregate) {
        String openid = aggregate.getOpenid();
        ProductEntity product = aggregate.getProduct();
        OrderEntity order = aggregate.getOrder();
        OpenAIOrderPO openAIOrderPO = new OpenAIOrderPO();
        openAIOrderPO.setOpenid(openid);
        openAIOrderPO.setProductId(product.getProductId());
        openAIOrderPO.setProductName(product.getProductName());
        openAIOrderPO.setProductCreditAmount(product.getCreditAmount());
        openAIOrderPO.setProductFeeRate(product.getFeeRate());
        openAIOrderPO.setProductFeeAmount(product.getFeeAmount());
        openAIOrderPO.setOrderId(order.getOrderId());
        openAIOrderPO.setOrderTime(order.getOrderTime());
        openAIOrderPO.setOrderStatus(order.getOrderStatus().getCode());
        openAIOrderPO.setTotalAmount(order.getTotalAmount());
        openAIOrderPO.setPayType(order.getPayTypeVO().getCode());
        openAIOrderPO.setPayStatus(PayStatusVO.WAIT.getCode());
        openAIOrderDao.insert(openAIOrderPO);
    }

    @Override
    public void updateOrderPayInfo(PayOrderEntity payOrderEntity) {
        OpenAIOrderPO openAIOrderPO = new OpenAIOrderPO();
        openAIOrderPO.setOpenid(payOrderEntity.getOpenid());
        openAIOrderPO.setOrderId(payOrderEntity.getOrderId());
        openAIOrderPO.setPayUrl(payOrderEntity.getPayUrl());
        openAIOrderPO.setPayStatus(payOrderEntity.getPayStatus().getCode());
        openAIOrderDao.updateOrderPayInfo(openAIOrderPO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 350, propagation = Propagation.REQUIRED, isolation = Isolation.DEFAULT)
    public boolean changeOrderPaySuccess(String orderId, String transactionId, BigDecimal totalAmount, Date payTime) {
        OpenAIOrderPO openAIOrderPO = new OpenAIOrderPO();
        openAIOrderPO.setOrderId(orderId);
        openAIOrderPO.setPayAmount(totalAmount);
        openAIOrderPO.setPayTime(payTime);
        openAIOrderPO.setTransactionId(transactionId);
        int count = openAIOrderDao.changeOrderPaySuccess(openAIOrderPO);
        if (count == 1) {
            paymentEventOutboxDao.insertIgnore(PaymentEventOutboxPO.builder()
                    .eventType(PaymentEventType.PAID.getCode())
                    .orderId(orderId)
                    .status(PaymentEventOutboxPO.PENDING)
                    .nextAttemptAt(new Date())
                    .build());
        }
        return count == 1;
    }

    @Override
    public CreateOrderAggregate queryOrder(String orderId) {
        OpenAIOrderPO openAIOrderPO = openAIOrderDao.queryOrder(orderId);

        if (openAIOrderPO == null) return null;

        ProductEntity product = new ProductEntity();
        product.setProductId(openAIOrderPO.getProductId());
        product.setProductName(openAIOrderPO.getProductName());
        product.setCreditAmount(openAIOrderPO.getProductCreditAmount());
        product.setFeeRate(openAIOrderPO.getProductFeeRate());
        product.setFeeAmount(openAIOrderPO.getProductFeeAmount());

        OrderEntity order = new OrderEntity();
        order.setOrderId(openAIOrderPO.getOrderId());
        order.setOrderTime(openAIOrderPO.getOrderTime());
        order.setOrderStatus(OrderStatusVO.get(openAIOrderPO.getOrderStatus()));
        order.setTotalAmount(openAIOrderPO.getTotalAmount());
        order.setPayTypeVO(cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO.get(openAIOrderPO.getPayType()));

        CreateOrderAggregate createOrderAggregate = new CreateOrderAggregate();
        createOrderAggregate.setOpenid(openAIOrderPO.getOpenid());
        createOrderAggregate.setOrder(order);
        createOrderAggregate.setProduct(product);

        return createOrderAggregate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 350, propagation = Propagation.REQUIRED, isolation = Isolation.DEFAULT)
    public void deliverGoods(String orderId) {
        OpenAIOrderPO openAIOrderPO = openAIOrderDao.queryOrder(orderId);
        if (openAIOrderPO == null) return;

        if (openAIOrderPO.getProductCreditAmount() == null) {
            // Historical orders without a wallet snapshot require manual handling.
            return;
        }

        // 1. 变更发货状态
        int updateOrderStatusDeliverGoodsCount = openAIOrderDao.updateOrderStatusDeliverGoods(orderId);
        if (updateOrderStatusDeliverGoodsCount == 0) return;

        // Wallet purchases still need an account row for the chat path.
        ensureAccount(openAIOrderPO.getOpenid());
        grantWalletBalance(openAIOrderPO);

        // The rebate event is committed with the delivery CAS and wallet grant.
        // A rollback therefore leaves the order eligible for replenishment.
        paymentEventOutboxDao.insertIgnore(PaymentEventOutboxPO.builder()
                .eventType(PaymentEventType.DELIVERED.getCode())
                .orderId(orderId)
                .status(PaymentEventOutboxPO.PENDING)
                .nextAttemptAt(new Date())
                .build());
    }

    private void ensureAccount(String openid) {
        UserAccountPO userAccountPO = userAccountDao.queryUserAccount(openid);
        if (userAccountPO != null) return;

        UserAccountPO userAccountPOReq = new UserAccountPO();
        userAccountPOReq.setOpenid(openid);
        userAccountPOReq.setStatus(UserAccountStatusVO.AVAILABLE.getCode());
        userAccountPOReq.setModelTypes(modelTypes);
        userAccountDao.insert(userAccountPOReq);
    }

    private void grantWalletBalance(OpenAIOrderPO order) {
        BigDecimal creditAmount = order.getProductCreditAmount();
        if (creditAmount.signum() <= 0) {
            throw new IllegalStateException("Wallet purchase credit amount must be positive");
        }

        BalanceGrantPO grant = BalanceGrantPO.builder()
                .appId(PAYMENT_APP_ID)
                .requestId(order.getOrderId())
                .openid(order.getOpenid())
                .amount(creditAmount)
                .source(WALLET_GRANT_SOURCE)
                .build();
        int inserted = balanceGrantDao.insertIgnore(grant);
        if (inserted == 1) {
            int walletRows = accountWalletDao.addOrCreateWallet(AccountWalletPO.builder()
                    .openid(order.getOpenid())
                    .currency(AccountBalanceVO.CURRENCY_CNY)
                    .availableAmount(creditAmount)
                    .totalAmount(creditAmount)
                    .build());
            if (walletRows < 1) {
                throw new IllegalStateException("Unable to apply wallet purchase");
            }
            return;
        }

        BalanceGrantPO existing = balanceGrantDao.query(PAYMENT_APP_ID, order.getOrderId());
        if (existing == null
                || !order.getOpenid().equals(existing.getOpenid())
                || existing.getAmount() == null
                || creditAmount.compareTo(existing.getAmount()) != 0) {
            throw new IllegalStateException("Wallet purchase grant payload does not match order");
        }
    }

    @Override
    public List<String> queryReplenishmentOrder() {
        return openAIOrderDao.queryReplenishmentOrder(new Date(System.currentTimeMillis() - 3 * 60_000L));
    }

    @Override
    public List<String> queryNoPayNotifyOrder() {
        return openAIOrderDao.queryNoPayNotifyOrder(new Date(System.currentTimeMillis() - 60_000L));
    }

    @Override
    public List<String> queryTimeoutCloseOrderList() {
        return openAIOrderDao.queryTimeoutCloseOrderList(new Date(System.currentTimeMillis() - 30 * 60_000L));
    }

    @Override
    public boolean changeOrderClose(String orderId) {
        return openAIOrderDao.changeOrderClose(orderId);
    }

    @Override
    public List<ProductEntity> queryProductList() {
        List<OpenAIProductPO> openAIProductPOList =  openAIProductDao.queryProductList();
        List<ProductEntity> productEntityList = new ArrayList<>(openAIProductPOList.size());
        for (OpenAIProductPO openAIProductPO : openAIProductPOList) {
            ProductEntity productEntity = new ProductEntity();
            productEntity.setProductId(openAIProductPO.getProductId());
            productEntity.setProductName(openAIProductPO.getProductName());
            productEntity.setProductDesc(openAIProductPO.getProductDesc());
            productEntity.setPrice(openAIProductPO.getPrice());
            productEntity.setCreditAmount(openAIProductPO.getCreditAmount());
            productEntity.applyRechargeFee();
            productEntityList.add(productEntity);
        }
        return productEntityList;
    }

}
