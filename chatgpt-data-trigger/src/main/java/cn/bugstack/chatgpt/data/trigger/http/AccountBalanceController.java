package cn.bugstack.chatgpt.data.trigger.http;

import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustBalanceEntity;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;
import cn.bugstack.chatgpt.data.domain.account.service.IBalanceGrantService;
import cn.bugstack.chatgpt.data.domain.account.service.IBalanceQueryService;
import cn.bugstack.chatgpt.data.domain.auth.service.IAuthService;
import cn.bugstack.chatgpt.data.trigger.http.dto.AccountBalanceResponseDTO;
import cn.bugstack.chatgpt.data.trigger.http.dto.AdjustBalanceRequestDTO;
import cn.bugstack.chatgpt.data.types.common.Constants;
import cn.bugstack.chatgpt.data.types.model.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;
import cn.bugstack.chatgpt.data.domain.account.service.IAccountQueryService;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountQuotaVO;

/**
 * CNY wallet grant and query endpoints.
 */
@RestController
@CrossOrigin("${app.config.cross-origin}")
@RequestMapping("/api/${app.config.api-version}/account/")
public class AccountBalanceController {

    private static final String GRANT_SOURCE = "lottery";

    @Resource
    private Map<String, String> appTokenMap;
    @Resource
    private IBalanceGrantService balanceGrantService;
    @Resource
    private IBalanceQueryService balanceQueryService;
    @Resource
    private IAuthService authService;
    @Resource
    private IAccountQueryService accountQueryService;

    @RequestMapping(value = "adjust_balance", method = RequestMethod.POST)
    public Response<AccountBalanceResponseDTO> adjustBalance(@RequestBody AdjustBalanceRequestDTO request) {
        if (request == null || StringUtils.isBlank(request.getAppId())
                || StringUtils.isBlank(request.getAppToken())) {
            return failure(Constants.ResponseCode.ILLEGAL_PARAMETER);
        }
        String expectedToken = appTokenMap.get(request.getAppId());
        if (expectedToken == null || !expectedToken.equals(request.getAppToken())) {
            return failure(Constants.ResponseCode.APP_TOKEN_ERROR);
        }
        try {
            AccountBalanceVO balance = balanceGrantService.adjustBalance(AdjustBalanceEntity.builder()
                    .appId(request.getAppId())
                    .openid(request.getOpenid())
                    .requestId(request.getRequestId())
                    .increaseAmount(request.getIncreaseAmount())
                    .source(GRANT_SOURCE)
                    .build());
            return success(balance);
        } catch (IllegalArgumentException e) {
            return failure(Constants.ResponseCode.ILLEGAL_PARAMETER);
        } catch (Exception e) {
            return failure(Constants.ResponseCode.UN_ERROR);
        }
    }

    @RequestMapping(value = "query_account_balance", method = RequestMethod.POST)
    public Response<AccountBalanceResponseDTO> queryAccountBalance(
            @RequestHeader("Authorization") String token) {
        try {
            if (!authService.checkToken(token)) {
                return failure(Constants.ResponseCode.TOKEN_ERROR);
            }
            String openid = authService.openid(token);
            AccountBalanceVO balance = balanceQueryService.queryBalance(openid);
            Response<AccountBalanceResponseDTO> response = success(balance);
            AccountQuotaVO legacy = accountQueryService.queryAccountQuota(openid);
            response.getData().setBillingMode("TOKEN");
            response.getData().setLegacyQuota(legacy == null || legacy.getSurplusQuota() == null
                    ? 0 : legacy.getSurplusQuota());
            return response;
        } catch (IllegalArgumentException e) {
            return failure(Constants.ResponseCode.ILLEGAL_PARAMETER);
        } catch (Exception e) {
            return failure(Constants.ResponseCode.UN_ERROR);
        }
    }

    private static Response<AccountBalanceResponseDTO> success(AccountBalanceVO balance) {
        return Response.<AccountBalanceResponseDTO>builder()
                .code(Constants.ResponseCode.SUCCESS.getCode())
                .info(Constants.ResponseCode.SUCCESS.getInfo())
                .data(AccountBalanceResponseDTO.from(balance))
                .build();
    }

    private static Response<AccountBalanceResponseDTO> failure(Constants.ResponseCode code) {
        return Response.<AccountBalanceResponseDTO>builder()
                .code(code.getCode())
                .info(code.getInfo())
                .build();
    }
}
