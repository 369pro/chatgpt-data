package cn.bugstack.chatgpt.data.domain.auth.service;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.google.common.cache.Cache;

import cn.bugstack.chatgpt.data.domain.auth.model.entity.AuthStateEntity;
import cn.bugstack.chatgpt.data.domain.auth.model.valobj.AuthTypeVO;
import cn.bugstack.chatgpt.data.domain.auth.repository.IAuthRepository;
import io.jsonwebtoken.Claims;
import cn.bugstack.chatgpt.data.domain.auth.model.entity.AccountCredentialEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 鉴权服务
 * @create 2023-08-05 18:23
 */
@Slf4j
@Service
public class AuthService extends AbstractAuthService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final String dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());

    @Override
    public AuthStateEntity passwordLogin(String username, String password, String clientAddress) {
        username = normalizeUsername(username);
        if (!validLoginCredentials(username, password)) return failure("0004", "账号或密码错误");
        if (!repository.acquireAuthPermit("login-ip:" + clientAddress, 60, 900)
                || !repository.acquireAuthPermit("login-user:" + username, 15, 900)) {
            return failure("0006", "操作过于频繁，请稍后再试");
        }
        AccountCredentialEntity account = repository.findAccount(username);
        boolean matched = passwordEncoder.matches(password,
                account == null ? dummyPasswordHash : account.getPasswordHash());
        if (account == null || !matched) return failure("0004", "账号或密码错误");
        return issueSession(account.getOpenId());
    }

    @Override
    public AuthStateEntity register(String username, String password, String clientAddress) {
        username = normalizeUsername(username);
        if (!validCredentials(username, password)) {
            return failure("0004", "账号需为4-32位字母、数字或下划线，密码需为8-64位且不超过72字节");
        }
        if (!repository.acquireAuthPermit("register-ip:" + clientAddress, 10, 3600)) {
            return failure("0006", "注册过于频繁，请稍后再试");
        }
        AccountCredentialEntity account = new AccountCredentialEntity(username,
                "u_" + UUID.randomUUID().toString().replace("-", ""), passwordEncoder.encode(password));
        if (!repository.createAccount(account)) return failure("0005", "账号已存在，请直接登录");
        return issueSession(account.getOpenId());
    }

    private AuthStateEntity issueSession(String openId) {
        long ttl = 7L * 24 * 60 * 60 * 1000;
        String token = encode(openId, ttl, Map.of("openId", openId));
        repository.cacheToken(token, openId, ttl);
        return AuthStateEntity.builder().code("0000").info("登录成功").openId(openId).token(token).build();
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validCredentials(String username, String password) {
        return validLoginCredentials(username, password) && password.length() >= 8;
    }

    private static boolean validLoginCredentials(String username, String password) {
        return username.matches("[a-z0-9_]{4,32}") && password != null
                && !password.isEmpty() && password.length() <= 64
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private static AuthStateEntity failure(String code, String info) {
        return AuthStateEntity.builder().code(code).info(info).build();
    }

    @Override
    public void logout(String token) {
        if (StringUtils.isNotBlank(token)) repository.removeToken(token);
    }

    @Resource
    private Cache<String, String> codeCache;
    @Resource
    private IAuthRepository repository;

    @Override
    protected AuthStateEntity checkCode(String code) {
        // 获取验证码校验
        String openId = repository.getCodeUserOpenId(code);
        if (StringUtils.isBlank(openId)){
            log.info("鉴权，用户收入的验证码不存在 {}", code);
            return AuthStateEntity.builder()
                    .code(AuthTypeVO.A0001.getCode())
                    .info(AuthTypeVO.A0001.getInfo())
                    .build();
        }

        // 移除缓存Key值
        repository.removeCodeByOpenId(code, openId);

        // 验证码校验成功
        return AuthStateEntity.builder()
                .code(AuthTypeVO.A0000.getCode())
                .info(AuthTypeVO.A0000.getInfo())
                .openId(openId)
                .build();
    }

    @Override
    public AuthStateEntity doLogin(String code) {
        AuthStateEntity authStateEntity = super.doLogin(code);
        if (AuthTypeVO.A0000.getCode().equals(authStateEntity.getCode()) && StringUtils.isNotBlank(authStateEntity.getToken())) {
            repository.cacheToken(authStateEntity.getToken(), authStateEntity.getOpenId(), 7 * 24 * 60 * 60 * 1000L);
        }
        return authStateEntity;
    }

    @Override
    public boolean checkToken(String token) {
        if (StringUtils.isBlank(token)) return false;
        if (!isVerify(token)) return false;
        return StringUtils.isNotBlank(repository.getTokenOpenId(token));
    }

    @Override
    public String openid(String token) {
        Claims claims = decode(token);
        return claims.get("openId").toString();
    }

}
