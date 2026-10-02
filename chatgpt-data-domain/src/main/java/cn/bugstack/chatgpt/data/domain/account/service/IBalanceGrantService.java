package cn.bugstack.chatgpt.data.domain.account.service;

import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustBalanceEntity;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;

public interface IBalanceGrantService {

    AccountBalanceVO adjustBalance(AdjustBalanceEntity adjustBalanceEntity);
}
