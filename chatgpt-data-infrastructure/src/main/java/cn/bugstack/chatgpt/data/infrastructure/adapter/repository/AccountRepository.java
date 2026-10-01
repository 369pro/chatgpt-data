package cn.bugstack.chatgpt.data.infrastructure.adapter.repository;

import cn.bugstack.chatgpt.data.domain.account.adapter.repository.IAccountRepository;
import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustQuotaEntity;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountQuotaVO;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.UserAccountStatusVO;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountQuotaGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountQuotaGrantPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 账户仓储服务
 * @create 2024-10-06 09:14
 */
@Repository
public class AccountRepository implements IAccountRepository {

    @Value("${app.config.model-types:glm-4,gpt-4,gpt-3.5-turbo}")
    private String modelTypes;

    @Resource
    private IUserAccountDao userAccountDao;
    @Resource
    private IAccountQuotaGrantDao accountQuotaGrantDao;

    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public AccountQuotaVO adjustQuota(AdjustQuotaEntity adjustQuotaEntity) {

        String openid = adjustQuotaEntity.getOpenid();
        String requestId = adjustQuotaEntity.getRequestId();
        Integer increaseQuota = adjustQuotaEntity.getIncreaseQuota();
        if (openid == null || openid.trim().isEmpty() || openid.length() > 64) {
            throw new IllegalArgumentException("openid is required and must be at most 64 characters");
        }
        if (requestId == null || requestId.trim().isEmpty() || requestId.length() > 128) {
            throw new IllegalArgumentException("requestId is required and must be at most 128 characters");
        }
        if (increaseQuota == null || increaseQuota <= 0) {
            throw new IllegalArgumentException("increaseQuota must be positive");
        }

        AccountQuotaGrantPO grant = AccountQuotaGrantPO.builder()
                .openid(openid)
                .requestId(requestId)
                .increaseQuota(increaseQuota)
                .build();
        int inserted = accountQuotaGrantDao.insertIgnore(grant);
        if (inserted == 1) {
            UserAccountPO userAccountPOReq = new UserAccountPO();
            userAccountPOReq.setOpenid(openid);
            userAccountPOReq.setTotalQuota(increaseQuota);
            userAccountPOReq.setSurplusQuota(increaseQuota);
            userAccountPOReq.setStatus(UserAccountStatusVO.AVAILABLE.getCode());
            userAccountPOReq.setModelTypes(modelTypes);
            int accountRows = userAccountDao.addOrCreateAccountQuota(userAccountPOReq);
            if (accountRows < 1) {
                throw new IllegalStateException("Unable to apply quota grant");
            }
        } else {
            AccountQuotaGrantPO existing = accountQuotaGrantDao.query(
                    openid, requestId);
            if (existing == null) {
                throw new IllegalStateException("Unable to verify quota grant request");
            }
            if (!increaseQuota.equals(existing.getIncreaseQuota())) {
                throw new IllegalArgumentException("requestId reused with a different quota");
            }
        }

        UserAccountPO userAccountPO = userAccountDao.queryUserAccount(openid);
        if (userAccountPO == null) {
            throw new IllegalStateException("Quota grant account was not found");
        }

        // 返回结果
        return AccountQuotaVO.builder()
                .totalQuota(userAccountPO.getTotalQuota())
                .surplusQuota(userAccountPO.getSurplusQuota())
                .build();
    }

    @Override
    public AccountQuotaVO queryAccountQuota(String openid) {
        UserAccountPO userAccountPO = userAccountDao.queryUserAccount(openid);
        if (null == userAccountPO) return AccountQuotaVO.builder().totalQuota(0).surplusQuota(0).build();
        return AccountQuotaVO.builder()
                .totalQuota(userAccountPO.getTotalQuota())
                .surplusQuota(userAccountPO.getSurplusQuota())
                .build();
    }

}
