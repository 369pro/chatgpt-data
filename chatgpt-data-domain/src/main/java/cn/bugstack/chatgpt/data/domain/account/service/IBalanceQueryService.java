package cn.bugstack.chatgpt.data.domain.account.service;

import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;

public interface IBalanceQueryService {

    AccountBalanceVO queryBalance(String openid);
}
