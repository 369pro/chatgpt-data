package cn.bugstack.chatgpt.data.trigger.http;

import cn.bugstack.chatgpt.data.domain.auth.service.IAuthService;
import cn.bugstack.chatgpt.data.domain.order.model.entity.*;
import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.domain.order.service.IOrderService;
import cn.bugstack.chatgpt.data.trigger.http.dto.SaleProductDTO;
import cn.bugstack.chatgpt.data.types.model.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@CrossOrigin("${app.config.cross-origin}")
@RequestMapping("/api/${app.config.api-version}/sale/")
public class SaleController {
    @Resource private IOrderService orderService;
    @Resource private IAuthService authService;

    @GetMapping("query_product_list")
    public Response<List<SaleProductDTO>> queryProductList(@RequestHeader("Authorization") String token) {
        if (!authService.checkToken(token)) return loginRequired();
        List<SaleProductDTO> products = orderService.queryProductList().stream().map(p ->
                SaleProductDTO.builder().productId(p.getProductId()).productName(p.getProductName())
                        .productDesc(p.getProductDesc()).price(p.getPrice()).quota(p.getQuota())
                        .creditAmount(p.getCreditAmount()).build()
        ).collect(Collectors.toList());
        return success(products);
    }

    @PostMapping("create_pay_order")
    public Response<Map<String, Object>> createPayOrder(@RequestHeader("Authorization") String token,
            @RequestParam Integer productId, @RequestParam(defaultValue = "ALIPAY_SANDBOX") String channel) {
        if (!authService.checkToken(token)) return loginRequired();
        try {
            PayTypeVO type = PayTypeVO.valueOf(channel);
            PayOrderEntity payment = orderService.createOrder(ShopCartEntity.builder()
                    .openid(authService.openid(token)).productId(productId).payType(type).build());
            Map<String, Object> data = status(orderService.queryOrder(payment.getOrderId()));
            data.put("payUrl", payment.getPayUrl());
            return success(data);
        } catch (IllegalArgumentException e) {
            return failure("商品不可购买或支付渠道不支持");
        } catch (IllegalStateException e) {
            log.warn("Payment channel unavailable: {}", e.getMessage());
            return failure("支付渠道暂不可用，请检查沙箱配置");
        } catch (Exception e) {
            log.error("Create payment failed", e);
            return failure("下单失败，请稍后重试");
        }
    }

    @GetMapping("query_pay_order")
    public Response<Map<String, Object>> queryPayOrder(@RequestHeader("Authorization") String token,
                                                       @RequestParam String orderId) {
        if (!authService.checkToken(token)) return loginRequired();
        CreateOrderAggregate order = orderService.queryOrder(orderId);
        if (order == null || !authService.openid(token).equals(order.getOpenid())) {
            return failure("订单不存在");
        }
        try {
            orderService.reconcileOrder(orderId);
            return success(status(orderService.queryOrder(orderId)));
        } catch (Exception e) {
            log.warn("Payment query temporarily unavailable for order {}", orderId);
            return failure("支付结果确认中，请稍后查询，不要重复付款");
        }
    }

    @PostMapping(value = "alipay/notify", produces = "text/plain;charset=UTF-8")
    public String alipayNotify(@RequestParam Map<String, String> parameters) {
        try {
            orderService.acceptNotification(PayTypeVO.ALIPAY_SANDBOX, parameters);
            return "success";
        } catch (Exception e) {
            log.warn("Alipay notification rejected: {}", e.getClass().getSimpleName());
            return "failure";
        }
    }

    @PostMapping(value = "pay_notify", produces = "text/plain;charset=UTF-8")
    public String wechatNotify() {
        return "failure";
    }

    private Map<String, Object> status(CreateOrderAggregate aggregate) {
        OrderEntity order = aggregate.getOrder();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.getOrderId());
        data.put("status", order.getOrderStatus().name());
        data.put("channel", order.getPayTypeVO().name());
        data.put("amount", order.getTotalAmount());
        data.put("quota", aggregate.getProduct().getQuota());
        data.put("creditAmount", aggregate.getProduct().getCreditAmount());
        data.put("productName", aggregate.getProduct().getProductName());
        data.put("expiresAt", order.getOrderTime().getTime() + 30 * 60_000L);
        return data;
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code("0000").info("成功").data(data).build();
    }
    private <T> Response<T> failure(String info) {
        return Response.<T>builder().code("0001").info(info).build();
    }
    private <T> Response<T> loginRequired() {
        return Response.<T>builder().code(
                cn.bugstack.chatgpt.data.types.common.Constants.ResponseCode.TOKEN_ERROR.getCode())
                .info("请重新登录").build();
    }
}
