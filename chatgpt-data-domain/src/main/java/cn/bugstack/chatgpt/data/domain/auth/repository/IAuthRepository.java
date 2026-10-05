package cn.bugstack.chatgpt.data.domain.auth.repository;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 认证仓储服务
 * @create 2023-11-05 15:52
 */
public interface IAuthRepository {
    cn.bugstack.chatgpt.data.domain.auth.model.entity.AccountCredentialEntity findAccount(String username);

    boolean createAccount(cn.bugstack.chatgpt.data.domain.auth.model.entity.AccountCredentialEntity account);

    boolean acquireAuthPermit(String key, int limit, int windowSeconds);

    void removeToken(String token);

    String getCodeUserOpenId(String code);

    void removeCodeByOpenId(String code, String openId);

    void cacheCodeOpenId(String code, String openId, long expiredMillis);

    void cacheToken(String token, String openId, long expiredMillis);

    String getTokenOpenId(String token);

}
