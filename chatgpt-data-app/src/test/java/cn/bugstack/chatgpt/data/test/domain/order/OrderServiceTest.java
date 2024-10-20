package cn.bugstack.chatgpt.data.test.domain.order;

import cn.bugstack.chatgpt.data.domain.order.model.entity.PayOrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ShopCartEntity;
import cn.bugstack.chatgpt.data.domain.order.service.IOrderService;
import com.alibaba.fastjson2.JSON;
import com.google.common.eventbus.EventBus;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.CountDownLatch;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 订单服务测试
 * @create 2023-10-05 16:49
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class OrderServiceTest {

    @Resource
    private IOrderService orderService;

    @Resource
    private EventBus eventBus;

    @Test
    public void test_createOrder() {
        ShopCartEntity shopCartEntity = ShopCartEntity.builder()
                .openid("xfg-test02")
                .productId(1001)
                .build();
        PayOrderEntity payOrderEntity = orderService.createOrder(shopCartEntity);
        log.info("请求参数：{} 测试结果: {}", shopCartEntity, payOrderEntity);
    }

    @Test
    public void test_pay_success() {
        orderService.changeOrderPaySuccess("515019067766", "515019067766", BigDecimal.TEN, new Date());
    }

    @Test
    public void test_mq() throws InterruptedException {
        eventBus.post("515019067766");

        new CountDownLatch(1).await();
    }

}
