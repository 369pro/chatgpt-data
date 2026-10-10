package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Database-backed payment event. Sending and consuming are deliberately
 * represented by one lifecycle status: sent is not done.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentEventOutboxPO {

    public static final int PENDING = 0;
    public static final int SENT = 1;
    public static final int DONE = 2;
    /** Manual pause/dead-letter review state; no automatic transition is implemented. */
    public static final int DEAD = 3;

    private Long id;
    private String eventType;
    private String orderId;
    private Integer status;
    private Integer sendAttempts;
    private Integer consumeAttempts;
    private Date nextAttemptAt;
    private Date sentAt;
    private Date doneAt;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
