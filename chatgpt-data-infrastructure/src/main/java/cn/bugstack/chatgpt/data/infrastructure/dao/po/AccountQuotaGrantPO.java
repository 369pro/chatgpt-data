package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * One idempotent quota grant request.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AccountQuotaGrantPO {

    private Long id;
    private String openid;
    private String requestId;
    private Integer increaseQuota;
    private Date createTime;
}
