package cn.bugstack.chatgpt.data.test.auth;

import cn.bugstack.chatgpt.data.domain.auth.model.entity.AccountCredentialEntity;
import cn.bugstack.chatgpt.data.domain.auth.model.entity.AuthStateEntity;
import cn.bugstack.chatgpt.data.domain.auth.repository.IAuthRepository;
import cn.bugstack.chatgpt.data.domain.auth.service.AuthService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PasswordAuthTest {
    private AuthService service;
    private IAuthRepository repository;
    private final Map<String, AccountCredentialEntity> accounts = new HashMap<>();
    private final Map<String, String> tokens = new HashMap<>();

    @Before
    public void setUp() {
        repository = mock(IAuthRepository.class);
        service = new AuthService();
        service.configureJwtSecret("test-only-signing-key-at-least-32-bytes");
        ReflectionTestUtils.setField(service, "repository", repository);
        when(repository.acquireAuthPermit(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(repository.createAccount(any())).thenAnswer(call -> {
            AccountCredentialEntity account = call.getArgument(0);
            return accounts.putIfAbsent(account.getUsername(), account) == null;
        });
        when(repository.findAccount(anyString())).thenAnswer(call -> accounts.get(call.getArgument(0)));
        doAnswer(call -> {tokens.put(call.getArgument(0), call.getArgument(1)); return null;})
                .when(repository).cacheToken(anyString(), anyString(), anyLong());
        when(repository.getTokenOpenId(anyString())).thenAnswer(call -> tokens.get(call.getArgument(0)));
        doAnswer(call -> {tokens.remove(call.getArgument(0)); return null;}).when(repository).removeToken(anyString());
    }

    @Test
    public void registrationHashesPasswordAndIssuesSession() {
        AuthStateEntity result = service.register(" Demo_User ", "A-strong-password!", "127.0.0.1");
        assertEquals("0000", result.getCode());
        String hash = accounts.get("demo_user").getPasswordHash();
        assertNotEquals("A-strong-password!", hash);
        assertTrue(new BCryptPasswordEncoder().matches("A-strong-password!", hash));
        assertTrue(service.checkToken(result.getToken()));
        assertEquals(result.getOpenId(), service.openid(result.getToken()));
    }

    @Test
    public void loginChecksPasswordAndNormalizesUsername() {
        service.register("demo_user", "A-strong-password!", "127.0.0.1");
        assertEquals("0000", service.passwordLogin("DEMO_USER", "A-strong-password!", "127.0.0.1").getCode());
        assertEquals("0004", service.passwordLogin("demo_user", "wrong-password", "127.0.0.1").getCode());
        assertEquals("0004", service.passwordLogin("not_found", "wrong-password", "127.0.0.1").getCode());
    }

    @Test
    public void duplicateRegistrationDoesNotReplaceCredentials() {
        service.register("demo_user", "A-strong-password!", "127.0.0.1");
        assertEquals("0005", service.register("DEMO_USER", "different-password", "127.0.0.1").getCode());
        assertEquals("0000", service.passwordLogin("demo_user", "A-strong-password!", "127.0.0.1").getCode());
    }

    @Test
    public void invalidInputsDoNotCreateAccounts() {
        assertEquals("0004", service.register("ab", "A-strong-password!", "127.0.0.1").getCode());
        assertEquals("0004", service.register("demo_user", "short", "127.0.0.1").getCode());
        assertEquals("0004", service.register("demo_user", "\u4e2d".repeat(25), "127.0.0.1").getCode());
        assertEquals("0004", service.passwordLogin(null, null, "127.0.0.1").getCode());
        verify(repository, never()).createAccount(any());
    }

    @Test
    public void rateLimitPreventsPasswordWork() {
        when(repository.acquireAuthPermit(anyString(), anyInt(), anyInt())).thenReturn(false);
        assertEquals("0006", service.passwordLogin("demo_user", "A-strong-password!", "127.0.0.1").getCode());
        assertEquals("0006", service.register("demo_user", "A-strong-password!", "127.0.0.1").getCode());
        verify(repository, never()).findAccount(anyString());
        verify(repository, never()).createAccount(any());
    }

    @Test
    public void logoutRevokesCachedToken() {
        String token = service.register("demo_user", "A-strong-password!", "127.0.0.1").getToken();
        service.logout(token);
        assertFalse(service.checkToken(token));
    }

    @Test
    public void oldTestCodeNoLongerBypassesAuthentication() {
        assertNotEquals("0000", service.doLogin("1234").getCode());
        verify(repository, never()).cacheToken(anyString(), anyString(), anyLong());
    }
}
