package cn.bugstack.chatgpt.data.infrastructure.gateway;

import com.pro.trigger.api.IRebateService;
import com.pro.trigger.api.dto.RebateRequestDTO;
import com.pro.trigger.api.request.Request;
import com.pro.trigger.api.response.Response;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 返利服务
 * @create 2024-10-20 14:27
 */
@Service
public class RebateServiceRPC {

    @DubboReference(interfaceClass = IRebateService.class, version = "1.0", check = false)
    private IRebateService rebateService;

    @Value("${app.config.big-market.appId}")
    private String appId;
    @Value("${app.config.big-market.appToken}")
    private String appToken;

    public boolean rebate(String userId, String orderId) {
        RebateRequestDTO requestDTO = new RebateRequestDTO();
        requestDTO.setUserId(userId);
        requestDTO.setOutBusinessNo(orderId);
        requestDTO.setBehaviorType("OPENAI_PAY");

        Request<RebateRequestDTO> request = new Request<>();
        request.setAppId(appId);
        request.setAppToken(appToken);
        request.setData(requestDTO);

        Response<Boolean> response;
        try {
            response = rebateService.rebate(request);
        } catch (RuntimeException e) {
            // The payment consumer must retry a failed marketing call. Do not
            // log the request because it contains the app token.
            throw new IllegalStateException("Marketing rebate RPC failed", e);
        }
        if (response == null || !"0000".equals(response.getCode())
                || !Boolean.TRUE.equals(response.getData())) {
            String code = response == null ? "null" : response.getCode();
            throw new IllegalStateException("Marketing rebate rejected, code=" + code);
        }
        return true;
    }

}
