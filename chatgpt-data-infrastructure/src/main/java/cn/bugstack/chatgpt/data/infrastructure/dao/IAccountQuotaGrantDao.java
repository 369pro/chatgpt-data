package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountQuotaGrantPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Durable idempotency ledger for account quota grants.
 */
@Mapper
public interface IAccountQuotaGrantDao {

    int insertIgnore(AccountQuotaGrantPO grant);

    AccountQuotaGrantPO query(@Param("openid") String openid, @Param("requestId") String requestId);
}
