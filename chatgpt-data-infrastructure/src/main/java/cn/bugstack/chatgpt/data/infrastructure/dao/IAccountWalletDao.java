package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountWalletPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * CNY wallet persistence operations.
 */
@Mapper
public interface IAccountWalletDao {

    int addOrCreateWallet(AccountWalletPO wallet);

    AccountWalletPO queryWallet(String openid);

    AccountWalletPO queryWalletForUpdate(String openid);
}
