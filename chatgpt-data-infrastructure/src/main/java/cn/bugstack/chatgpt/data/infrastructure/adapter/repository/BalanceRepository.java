package cn.bugstack.chatgpt.data.infrastructure.adapter.repository;

import cn.bugstack.chatgpt.data.domain.account.adapter.repository.IBalanceRepository;
import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustBalanceEntity;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.BalanceGrantAmount;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountWalletDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IBalanceGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountWalletPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.BalanceGrantPO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Transactional CNY wallet repository. The grant ledger and wallet update
 * commit together, while the unique app/request key makes retries harmless.
 */
@Repository
public class BalanceRepository implements IBalanceRepository {

    private final IUserAccountDao userAccountDao;
    private final IBalanceGrantDao balanceGrantDao;
    private final IAccountWalletDao accountWalletDao;

    public BalanceRepository(IUserAccountDao userAccountDao,
                             IBalanceGrantDao balanceGrantDao,
                             IAccountWalletDao accountWalletDao) {
        this.userAccountDao = userAccountDao;
        this.balanceGrantDao = balanceGrantDao;
        this.accountWalletDao = accountWalletDao;
    }

    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public AccountBalanceVO adjustBalance(AdjustBalanceEntity request) {
        validateRequest(request);

        if (userAccountDao.queryUserAccount(request.getOpenid()) == null) {
            throw new IllegalStateException("Balance grant account was not found");
        }

        BalanceGrantPO grant = BalanceGrantPO.builder()
                .appId(request.getAppId())
                .requestId(request.getRequestId())
                .openid(request.getOpenid())
                .amount(request.getIncreaseAmount())
                .source(request.getSource())
                .build();
        int inserted = balanceGrantDao.insertIgnore(grant);
        if (inserted == 1) {
            int walletRows = accountWalletDao.addOrCreateWallet(AccountWalletPO.builder()
                    .openid(request.getOpenid())
                    .currency(AccountBalanceVO.CURRENCY_CNY)
                    .availableAmount(request.getIncreaseAmount())
                    .totalAmount(request.getIncreaseAmount())
                    .build());
            if (walletRows < 1) {
                throw new IllegalStateException("Unable to apply balance grant");
            }
        } else {
            BalanceGrantPO existing = balanceGrantDao.query(request.getAppId(), request.getRequestId());
            if (existing == null) {
                throw new IllegalStateException("Unable to verify balance grant request");
            }
            if (!request.getOpenid().equals(existing.getOpenid())
                    || request.getIncreaseAmount().compareTo(existing.getAmount()) != 0) {
                throw new IllegalArgumentException("requestId reused with a different account or amount");
            }
        }

        AccountWalletPO wallet = accountWalletDao.queryWalletForUpdate(request.getOpenid());
        if (wallet == null) {
            throw new IllegalStateException("Balance wallet was not found");
        }
        return toVO(wallet);
    }

    @Override
    public AccountBalanceVO queryBalance(String openid) {
        AccountWalletPO wallet = accountWalletDao.queryWallet(openid);
        if (wallet == null) {
            return zeroBalance();
        }
        return toVO(wallet);
    }

    private static void validateRequest(AdjustBalanceEntity request) {
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
    }

    private static AccountBalanceVO zeroBalance() {
        return AccountBalanceVO.builder()
                .currency(AccountBalanceVO.CURRENCY_CNY)
                .availableAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .build();
    }

    private static AccountBalanceVO toVO(AccountWalletPO wallet) {
        return AccountBalanceVO.builder()
                .currency(StringUtils.defaultIfBlank(wallet.getCurrency(), AccountBalanceVO.CURRENCY_CNY))
                .availableAmount(wallet.getAvailableAmount() == null ? BigDecimal.ZERO : wallet.getAvailableAmount())
                .totalAmount(wallet.getTotalAmount() == null ? BigDecimal.ZERO : wallet.getTotalAmount())
                .build();
    }
}
