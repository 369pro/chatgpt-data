package cn.bugstack.chatgpt.data.test;

import cn.bugstack.trigger.api.IRaffleActivityService;
import cn.bugstack.trigger.api.IRebateService;
import cn.bugstack.trigger.api.dto.ActivityDrawRequestDTO;
import cn.bugstack.trigger.api.dto.ActivityDrawResponseDTO;
import cn.bugstack.trigger.api.dto.RebateRequestDTO;
import cn.bugstack.trigger.api.request.Request;
import cn.bugstack.trigger.api.response.Response;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class RpcTest {

    @DubboReference(interfaceClass = IRaffleActivityService.class, version = "1.0")
    private IRaffleActivityService raffleActivityService;

    @DubboReference(interfaceClass = IRebateService.class, version = "1.0")
    private IRebateService rebateService;

    @Test
    public void test_rpc() {
        ActivityDrawRequestDTO request = new ActivityDrawRequestDTO();
        request.setActivityId(100301L);
        request.setUserId("xiaofuge");
        Response<ActivityDrawResponseDTO> response = raffleActivityService.draw(request);

        log.info("请求参数：{}", JSON.toJSONString(request));
        log.info("测试结果：{}", JSON.toJSONString(response));
    }

    @Test
    public void test_rebate() {
        RebateRequestDTO requestDTO = new RebateRequestDTO();
        requestDTO.setUserId("xfg");
        requestDTO.setOutBusinessNo("100101001010001");
        requestDTO.setBehaviorType("OPENAI_PAY");

        Request<RebateRequestDTO> request = new Request<>();

        request.setAppId("chatgpt-data");
        request.setAppToken("89iu7o8732ijd9114");
        request.setData(requestDTO);

        Response<Boolean> response = rebateService.rebate(request);
        log.info("请求参数：{}", JSON.toJSONString(request));
        log.info("测试结果：{}", JSON.toJSONString(response));
    }

}
