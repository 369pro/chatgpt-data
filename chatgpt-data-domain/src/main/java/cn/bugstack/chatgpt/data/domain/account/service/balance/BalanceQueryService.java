package cn.bugstack.chatgpt.data.domain.account.service.balance;

import cn.bugstack.chatgpt.data.domain.account.adapter.repository.IBalanceRepository;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;
import cn.bugstack.chatgpt.data.domain.account.service.IBalanceQueryService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Reads the wallet without creating a row for accounts that have no grants.
 */
@Service
public class BalanceQueryService implements IBalanceQueryService {

    private final IBalanceRepository repository;

    public BalanceQueryService(IBalanceRepository repository) {
        this.repository = repository;
    }

    @Override
    public AccountBalanceVO queryBalance(String openid) {
        if (StringUtils.isBlank(openid) || openid.length() > 64) {
            throw new IllegalArgumentException("openid is required and bounded");
        }
        return repository.queryBalance(openid);
    }
}
