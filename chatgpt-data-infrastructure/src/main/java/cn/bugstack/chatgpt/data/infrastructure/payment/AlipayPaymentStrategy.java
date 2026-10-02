package cn.bugstack.chatgpt.data.infrastructure.payment;

import cn.bugstack.chatgpt.data.domain.order.adapter.port.PaymentStrategy;
import cn.bugstack.chatgpt.data.domain.order.model.entity.PaymentResult;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import com.alipay.api.*;
import com.alipay.api.domain.*;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.alipay.api.internal.util.AlipaySignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

@Component
public class AlipayPaymentStrategy implements PaymentStrategy {
    private final AlipayClient client;
    private final String appId, publicKey, sellerId, notifyUrl, returnUrl;
    private final boolean enabled;

    public AlipayPaymentStrategy(
            @Value("${payment.alipay.enabled:false}") boolean enabled,
            @Value("${payment.alipay.app-id:}") String appId,
            @Value("${payment.alipay.private-key:}") String privateKey,
            @Value("${payment.alipay.public-key:}") String publicKey,
            @Value("${payment.alipay.seller-id:}") String sellerId,
            @Value("${payment.alipay.notify-url:}") String notifyUrl,
            @Value("${payment.alipay.return-url:http://localhost:3003/}") String returnUrl) {
        this.enabled = enabled;
        this.appId = appId;
        this.publicKey = publicKey;
        this.sellerId = sellerId;
        this.notifyUrl = notifyUrl;
        this.returnUrl = returnUrl;
        // Sandbox only: configuration cannot accidentally select real payments.
        this.client = enabled && !privateKey.isBlank() ? new DefaultAlipayClient(
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do", appId,
                privateKey, "json", "UTF-8", publicKey, "RSA2") : null;
    }

    public PayTypeVO type() { return PayTypeVO.ALIPAY_SANDBOX; }

    public void checkAvailable() {
        if (!enabled || client == null || appId.isBlank() || publicKey.isBlank()) {
            throw new IllegalStateException("Alipay sandbox is not configured");
        }
    }

    private SimpleDateFormat dateFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format;
    }

    public String create(String orderId, String subject, BigDecimal amount, Date expiresAt) {
        checkAvailable();
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderId);
        model.setSubject(subject);
        model.setTotalAmount(amount.setScale(2).toPlainString());
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        model.setTimeExpire(dateFormat().format(expiresAt));
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        if (!notifyUrl.isBlank()) request.setNotifyUrl(notifyUrl);
        request.setReturnUrl(returnUrl);
        try {
            return client.pageExecute(request, "GET").getBody();
        } catch (AlipayApiException e) {
            throw new IllegalStateException("Cannot create Alipay checkout", e);
        }
    }

    public PaymentResult query(String orderId) {
        checkAvailable();
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(orderId);
        request.setBizModel(model);
        try {
            AlipayTradeQueryResponse response = client.execute(request);
            if (!response.isSuccess()) {
                if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) {
                    return new PaymentResult(orderId, null, null, null, false, false);
                }
                throw new IllegalStateException("Alipay query rejected: " + response.getSubCode());
            }
            if (!orderId.equals(response.getOutTradeNo())) throw new IllegalArgumentException("Order mismatch");
            return new PaymentResult(orderId, response.getTradeNo(),
                    new BigDecimal(response.getTotalAmount()), response.getSendPayDate(),
                    isPaid(response.getTradeStatus()), "TRADE_CLOSED".equals(response.getTradeStatus()));
        } catch (AlipayApiException e) {
            throw new IllegalStateException("Alipay query failed", e);
        }
    }

    public boolean close(String orderId) {
        checkAvailable();
        AlipayTradeCloseModel model = new AlipayTradeCloseModel();
        model.setOutTradeNo(orderId);
        AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
        request.setBizModel(model);
        try {
            AlipayTradeCloseResponse response = client.execute(request);
            return response.isSuccess() || "ACQ.TRADE_NOT_EXIST".equals(response.getSubCode());
        } catch (AlipayApiException e) {
            throw new IllegalStateException("Alipay close failed", e);
        }
    }

    public PaymentResult verifyNotification(Map<String, String> parameters) {
        checkAvailable();
        try {
            if (!"RSA2".equals(parameters.get("sign_type")) ||
                    !AlipaySignature.rsaCheckV1(new HashMap<>(parameters), publicKey, "UTF-8", "RSA2") ||
                    !appId.equals(parameters.get("app_id")) ||
                    (!sellerId.isBlank() && !sellerId.equals(parameters.get("seller_id")))) {
                throw new IllegalArgumentException("Invalid Alipay notification");
            }
            String status = parameters.get("trade_status");
            Date paidAt = parameters.get("gmt_payment") == null ? null : dateFormat().parse(parameters.get("gmt_payment"));
            return new PaymentResult(parameters.get("out_trade_no"), parameters.get("trade_no"),
                    new BigDecimal(parameters.get("total_amount")), paidAt, isPaid(status), "TRADE_CLOSED".equals(status));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Alipay notification", e);
        }
    }

    private boolean isPaid(String status) {
        return "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status);
    }
}
