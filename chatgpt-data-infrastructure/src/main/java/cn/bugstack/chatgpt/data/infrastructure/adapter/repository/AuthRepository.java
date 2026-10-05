package cn.bugstack.chatgpt.data.infrastructure.adapter.repository;

import cn.bugstack.chatgpt.data.domain.auth.repository.IAuthRepository;
import cn.bugstack.chatgpt.data.domain.auth.model.entity.AccountCredentialEntity;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAuthAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AuthAccountPO;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.springframework.dao.DuplicateKeyException;
import java.util.Collections;
import cn.bugstack.chatgpt.data.infrastructure.redis.IRedisService;
import org.redisson.api.RTransaction;
import org.redisson.api.TransactionOptions;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 认证仓储服务
 * @create 2023-11-05 15:53
 */
@Repository
public class AuthRepository implements IAuthRepository {

    private static final String CODE_KEY = "weixin_code";
    private static final String TOKEN_KEY = "auth_session";

    @Resource
    private IRedisService redisService;

    @Resource
    private IAuthAccountDao authAccountDao;
    @Resource
    private RedissonClient redissonClient;

    @Override
    public AccountCredentialEntity findAccount(String username) {
        AuthAccountPO account = authAccountDao.findByUsername(username);
        return account == null ? null : new AccountCredentialEntity(account.getUsername(),
                account.getOpenId(), account.getPasswordHash());
    }

    @Override
    public boolean createAccount(AccountCredentialEntity account) {
        AuthAccountPO po = new AuthAccountPO();
        po.setUsername(account.getUsername());
        po.setOpenId(account.getOpenId());
        po.setPasswordHash(account.getPasswordHash());
        try {
            return authAccountDao.insert(po) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean acquireAuthPermit(String key, int limit, int windowSeconds) {
        // Increment and expiry must be atomic so a crash cannot leave a permanent lockout.
        Long count = redissonClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n",
                RScript.ReturnType.INTEGER, Collections.singletonList("auth_rate:" + key), windowSeconds);
        return count <= limit;
    }

    @Override
    public void removeToken(String token) {
        redissonClient.getBucket(TOKEN_KEY + "_" + token, StringCodec.INSTANCE).delete();
    }

    @Override
    public String getCodeUserOpenId(String code) {
        return redisService.getValue(CODE_KEY + "_" + code);
    }

    @Override
    public void removeCodeByOpenId(String code, String openId) {
        // 创建一个事务选项实例，设置事务超时等参数
        TransactionOptions options = TransactionOptions.defaults()
                .timeout(3, TimeUnit.SECONDS);
        // 开始一个事务
        RTransaction transaction = redisService.createTransaction(options);
        try {
            redisService.remove(CODE_KEY + "_" + code);
            redisService.remove(CODE_KEY + "_" + openId);
            transaction.commit();
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
        }
    }

    @Override
    public void cacheCodeOpenId(String code, String openId, long expiredMillis) {
        redisService.setValue(CODE_KEY + "_" + code, openId, expiredMillis);
        redisService.setValue(CODE_KEY + "_" + openId, code, expiredMillis);
    }

    @Override
    public void cacheToken(String token, String openId, long expiredMillis) {
        redissonClient.getBucket(TOKEN_KEY + "_" + token, StringCodec.INSTANCE)
                .set(openId, expiredMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public String getTokenOpenId(String token) {
        return redissonClient.<String>getBucket(TOKEN_KEY + "_" + token, StringCodec.INSTANCE).get();
    }

}
