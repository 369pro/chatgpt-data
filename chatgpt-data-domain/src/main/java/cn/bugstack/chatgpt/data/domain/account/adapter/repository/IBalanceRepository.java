package cn.bugstack.chatgpt.data.domain.account.adapter.repository;

import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustBalanceEntity;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;

/**
 * Repository for the independent CNY wallet and its grant ledger.
 */
public interface IBalanceRepository {

    AccountBalanceVO adjustBalance(AdjustBalanceEntity adjustBalanceEntity);

    AccountBalanceVO queryBalance(String openid);
}
