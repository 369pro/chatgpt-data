package cn.bugstack.chatgpt.data.trigger.http;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Locale;

import com.alibaba.fastjson.JSON;

import cn.bugstack.chatgpt.data.domain.auth.model.entity.AuthStateEntity;
import cn.bugstack.chatgpt.data.domain.auth.model.valobj.AuthTypeVO;
import cn.bugstack.chatgpt.data.domain.auth.repository.IAuthRepository;
import cn.bugstack.chatgpt.data.domain.auth.service.IAuthService;
import cn.bugstack.chatgpt.data.domain.weixin.model.entity.MessageTextEntity;
import cn.bugstack.chatgpt.data.domain.weixin.model.entity.UserBehaviorMessageEntity;
import cn.bugstack.chatgpt.data.domain.weixin.model.valobj.MsgTypeVO;
import cn.bugstack.chatgpt.data.domain.weixin.service.IWeiXinBehaviorService;
import cn.bugstack.chatgpt.data.types.common.Constants;
import cn.bugstack.chatgpt.data.types.model.Response;
import cn.bugstack.chatgpt.data.types.sdk.weixin.XmlUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 鉴权登录
 * @create 2023-08-05 18:46
 */
@Slf4j
@RestController()
@CrossOrigin("${app.config.cross-origin}")
@RequestMapping("/api/${app.config.api-version}/auth/")
public class AuthController {

    @org.springframework.beans.factory.annotation.Value("${app.config.test-code-enabled:false}")
    private boolean testCodeEnabled;

    @RequestMapping(value = "password/login", method = RequestMethod.POST)
    public Response<Map<String, String>> passwordLogin(@RequestParam String username,
            @RequestParam String password, HttpServletRequest request) {
        try {
            return accountResponse(authService.passwordLogin(username, password, request.getRemoteAddr()), username);
        } catch (Exception e) {
            log.error("Account login unavailable", e);
            return Response.<Map<String, String>>builder().code("0001").info("登录服务暂不可用，请稍后重试").build();
        }
    }

    @RequestMapping(value = "register", method = RequestMethod.POST)
    public Response<Map<String, String>> register(@RequestParam String username,
            @RequestParam String password, HttpServletRequest request) {
        try {
            return accountResponse(authService.register(username, password, request.getRemoteAddr()), username);
        } catch (Exception e) {
            log.error("Account registration unavailable", e);
            return Response.<Map<String, String>>builder().code("0001").info("注册服务暂不可用，请稍后重试").build();
        }
    }

    @RequestMapping(value = "logout", method = RequestMethod.POST)
    public Response<Boolean> logout(@RequestHeader("Authorization") String token) {
        authService.logout(token);
        return Response.<Boolean>builder().code("0000").info("已退出登录").data(true).build();
    }

    private Response<Map<String, String>> accountResponse(AuthStateEntity state, String username) {
        Map<String, String> data = "0000".equals(state.getCode())
                ? Map.of("token", state.getToken(), "username", username.trim().toLowerCase(Locale.ROOT)) : null;
        return Response.<Map<String, String>>builder().code(state.getCode()).info(state.getInfo()).data(data).build();
    }

    @Resource
    private IAuthService authService;

    @Resource
    private IWeiXinBehaviorService weiXinBehaviorService;

    @Resource
    private IAuthRepository authRepository;

    public AuthController() {
        log.info("启动 AuthController");
    }

    /**
     * 生成验证码，用于测试使用
     * <p>
     * curl -X POST \
     *  http://apix.natapp1.cc/api/v1/auth/gen/code \
     * -H 'Content-Type: application/x-www-form-urlencoded' \
     * -d 'openid=oxfA9w8-23yvwTmo2ombz0E4zJv4'
     *
     * curl -X POST \
     *  http://localhost:8091/api/v1/auth/gen/code \
     * -H 'Content-Type: application/x-www-form-urlencoded' \
     * -d 'openid=oxfA9w8-23yvwTmo2ombz0E4zJv4'
     */
    @RequestMapping(value = "gen/code", method = RequestMethod.POST)
    public Response<String> genCode(@RequestParam String openid) {
        if (!testCodeEnabled) {
            return Response.<String>builder().code("0001").info("测试验证码接口未启用").build();
        }
        log.info("生成验证码开始，用户ID: {}", openid);
        try {
            UserBehaviorMessageEntity userBehaviorMessageEntity = new UserBehaviorMessageEntity();
            userBehaviorMessageEntity.setOpenId(openid);
            userBehaviorMessageEntity.setMsgType(MsgTypeVO.TEXT.getCode());
            userBehaviorMessageEntity.setContent("405");
            String xml = weiXinBehaviorService.acceptUserBehavior(userBehaviorMessageEntity);
            MessageTextEntity messageTextEntity = XmlUtil.xmlToBean(xml, MessageTextEntity.class);
            log.info("生成验证码完成，用户ID: {} 生成结果：{}", openid, messageTextEntity.getContent());
            return Response.<String>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(messageTextEntity.getContent())
                    .build();
        } catch (Exception e) {
            log.error("生成验证码失败，用户ID: {}", openid);
            return Response.<String>builder()
                    .code(Constants.ResponseCode.TOKEN_ERROR.getCode())
                    .info(Constants.ResponseCode.TOKEN_ERROR.getInfo())
                    .build();
        }
    }


    /**
     * 【apix.natapp1.cc 是我在 <a href="https://natapp.cn/">https://natapp.cn</a> 购买的渠道，你需要自己购买一个使用】
     * 鉴权，根据鉴权结果返回 Token 码
     * curl -X POST \
     * http://apix.natapp1.cc/api/v1/auth/login \
     * -H 'Content-Type: application/x-www-form-urlencoded' \
     * -d 'code=6880'
     * <p>
     * curl -X POST \
     * http://localhost:8091/api/v1/auth/login \
     * -H 'Content-Type: application/x-www-form-urlencoded' \
     * -d 'code=6880'
     */
    @RequestMapping(value = "login", method = RequestMethod.POST)
    public Response<String> doLogin(@RequestParam String code) {
        log.info("鉴权登录校验开始，验证码: {}", code);
        try {
            AuthStateEntity authStateEntity = authService.doLogin(code);
            log.info("鉴权登录校验完成，验证码: {} 结果: {}", code, JSON.toJSONString(authStateEntity));
            // 拦截，鉴权失败
            if (!AuthTypeVO.A0000.getCode().equals(authStateEntity.getCode())) {
                return Response.<String>builder()
                        .code(Constants.ResponseCode.TOKEN_ERROR.getCode())
                        .info(Constants.ResponseCode.TOKEN_ERROR.getInfo())
                        .build();
            }

            // 放行，鉴权成功
            return Response.<String>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(authStateEntity.getToken())
                    .build();

        } catch (Exception e) {
            log.error("鉴权登录校验失败，验证码: {}", code);
            return Response.<String>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

}
