package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.dao.po.PaymentEventOutboxPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * Persistence boundary for payment lifecycle events.
 */
@Mapper
public interface IPaymentEventOutboxDao {

    int insertIgnore(PaymentEventOutboxPO event);

    PaymentEventOutboxPO queryById(@Param("id") Long id);

    List<PaymentEventOutboxPO> queryReadyForSend(@Param("now") Date now,
                                                 @Param("limit") int limit);

    int markSent(@Param("id") Long id,
                 @Param("sentAt") Date sentAt,
                 @Param("nextAttemptAt") Date nextAttemptAt);

    int recordSendFailure(@Param("id") Long id,
                          @Param("nextAttemptAt") Date nextAttemptAt,
                          @Param("error") String error);

    int markDone(@Param("id") Long id, @Param("doneAt") Date doneAt);

    int recordConsumeFailure(@Param("id") Long id,
                             @Param("nextAttemptAt") Date nextAttemptAt,
                             @Param("error") String error);
}
