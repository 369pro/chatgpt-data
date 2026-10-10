package cn.bugstack.chatgpt.data.trigger.http.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticates the private Agent-to-Java hop before reading X-User-Id. */
@Component
public class AgentInternalRequestGuard {

    private final String expectedKey;

    public AgentInternalRequestGuard(@Value("${AGENT_INTERNAL_KEY:}") String expectedKey) {
        this.expectedKey = expectedKey == null ? "" : expectedKey;
    }

    public String userId(HttpServletRequest request) {
        if (!authorized(request.getHeader("X-Agent-Key"))) {
            throw new AgentHttpException(401, "agent service authentication failed");
        }
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank() || userId.length() > 64
                || userId.chars().anyMatch(Character::isISOControl)) {
            throw new AgentHttpException(400, "X-User-Id is required and bounded");
        }
        return userId;
    }

    private boolean authorized(String supplied) {
        if (expectedKey.isEmpty() || supplied == null) return false;
        return MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    public static final class AgentHttpException extends RuntimeException {
        private final int status;

        public AgentHttpException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
