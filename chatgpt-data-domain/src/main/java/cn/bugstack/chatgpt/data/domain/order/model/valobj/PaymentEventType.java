package cn.bugstack.chatgpt.data.domain.order.model.valobj;

/**
 * Durable payment lifecycle events. The event is kept in the database until
 * the consumer has completed the corresponding side effect.
 */
public enum PaymentEventType {

    PAID("PAID"),
    DELIVERED("DELIVERED");

    private final String code;

    PaymentEventType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static PaymentEventType fromCode(String code) {
        for (PaymentEventType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported payment event type: " + code);
    }
}
