package cn.bugstack.chatgpt.data.infrastructure.dao;

import cn.bugstack.chatgpt.data.infrastructure.dao.po.ChatQuotaPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.math.BigDecimal;

/**
 * Durable chat-quota reservation ledger access.
 */
@Mapper
public interface IChatQuotaDao {

    int insertReservation(ChatQuotaPO chatQuotaPO);

    int decrementUserQuota(@Param("openid") String openid);

    int debitWallet(@Param("openid") String openid, @Param("amount") BigDecimal amount);

    int recordWalletCharge(@Param("openid") String openid, @Param("requestId") String requestId,
                           @Param("amount") BigDecimal amount);

    ChatQuotaPO queryReservation(@Param("openid") String openid, @Param("requestId") String requestId);

    int refundWallet(@Param("openid") String openid, @Param("amount") BigDecimal amount);

    int recordSettlement(ChatQuotaPO reservation);

    int confirmReservation(@Param("openid") String openid, @Param("requestId") String requestId);

    int releaseReservation(@Param("openid") String openid, @Param("requestId") String requestId);

    int releaseReservationById(@Param("id") Long id);

    int refundUserQuota(@Param("openid") String openid);

    List<ChatQuotaPO> findExpiredReservations(@Param("expiredBefore") Date expiredBefore,
                                               @Param("limit") int limit);

}
