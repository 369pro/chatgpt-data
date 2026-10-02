package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.dao.po.BalanceGrantPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Durable idempotency ledger for CNY balance grants.
 */
@Mapper
public interface IBalanceGrantDao {

    int insertIgnore(BalanceGrantPO grant);

    BalanceGrantPO query(@Param("appId") String appId, @Param("requestId") String requestId);
}
