package cn.bugstack.chatgpt.data.domain.agent.service;

/**
 * A bounded, client-safe billing error. Messages deliberately contain no
 * credentials, SQL, or upstream response bodies.
 */
public class AgentBillingException extends RuntimeException {
    public enum Code {
        BAD_REQUEST,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        INSUFFICIENT_FUNDS,
        CAP_REACHED,
        UNAVAILABLE
    }

    private final Code code;

    public AgentBillingException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public AgentBillingException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
