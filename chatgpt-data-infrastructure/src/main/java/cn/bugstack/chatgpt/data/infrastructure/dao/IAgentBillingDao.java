package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.dao.po.AgentCallChargePO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AgentRunBudgetPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** SQL port for the independent Agent billing ledger. */
@Mapper
public interface IAgentBillingDao {

    int insertRun(AgentRunBudgetPO run);

    AgentRunBudgetPO findRun(@Param("userId") String userId, @Param("runId") String runId);

    AgentRunBudgetPO findRunForUpdate(@Param("userId") String userId, @Param("runId") String runId);

    int updateRun(AgentRunBudgetPO run);

    int insertCall(AgentCallChargePO call);

    AgentCallChargePO findCall(@Param("userId") String userId, @Param("callId") String callId);

    AgentCallChargePO findCallForUpdate(@Param("userId") String userId, @Param("callId") String callId);

    int updateCall(AgentCallChargePO call);

    /**
     * Locks the user's CNY wallet row for the duration of the billing
     * transaction. A null result means that no CNY wallet row exists.
     */
    BigDecimal findWalletAvailableForUpdate(@Param("userId") String userId);

    int debitWallet(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    int refundWallet(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    List<AgentCallChargePO> findStaleCalls(@Param("now") Date now, @Param("limit") int limit);
}
