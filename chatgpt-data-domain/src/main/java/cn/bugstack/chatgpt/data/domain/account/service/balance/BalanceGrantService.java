package cn.bugstack.chatgpt.data.domain.account.service.balance;

import cn.bugstack.chatgpt.data.domain.account.adapter.repository.IBalanceRepository;
import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustBalanceEntity;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.BalanceGrantAmount;
import cn.bugstack.chatgpt.data.domain.account.service.IBalanceGrantService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Validates and delegates CNY grant requests.
 */
@Service
public class BalanceGrantService implements IBalanceGrantService {

    private final IBalanceRepository repository;

    public BalanceGrantService(IBalanceRepository repository) {
        this.repository = repository;
    }

    @Override
    public AccountBalanceVO adjustBalance(AdjustBalanceEntity request) {
        if (request == null
                || StringUtils.isBlank(request.getAppId())
                || request.getAppId().length() > 64
                || StringUtils.isBlank(request.getOpenid())
                || request.getOpenid().length() > 64
                || StringUtils.isBlank(request.getRequestId())
                || request.getRequestId().length() > 160) {
            throw new IllegalArgumentException("appId, openid and requestId are required and bounded");
        }
        BalanceGrantAmount.validate(request.getIncreaseAmount());
        if (StringUtils.isBlank(request.getSource()) || request.getSource().length() > 64) {
            throw new IllegalArgumentException("source is required and bounded");
        }
        return repository.adjustBalance(request);
    }
}
