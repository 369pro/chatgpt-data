package cn.bugstack.chatgpt.data.infrastructure.adapter.repository;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.UserAccountStatusVO;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IChatQuotaDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.ChatQuotaPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatBillingQuote;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description OpenAi 仓储服务
 * @create 2023-10-03 17:14
 */
@Repository
@Slf4j
public class OpenAiRepository implements IOpenAiRepository {

    private static final int EXPIRED_RESERVATION_BATCH_SIZE = 100;
    private static final long RESERVATION_EXPIRY_MINUTES = 15L;


    @Resource
    private IUserAccountDao userAccountDao;
    @Resource
    private IChatQuotaDao chatQuotaDao;
    @Resource
    private PlatformTransactionManager transactionManager;

    @Override
    public int subAccountQuota(String openai) {
        return userAccountDao.subAccountQuota(openai);
    }

    @Override
    public UserAccountQuotaEntity queryUserAccount(String openid) {
        UserAccountPO userAccountPO = userAccountDao.queryUserAccount(openid);
        if (null == userAccountPO) return null;
        UserAccountQuotaEntity userAccountQuotaEntity = new UserAccountQuotaEntity();
        userAccountQuotaEntity.setOpenid(userAccountPO.getOpenid());
        userAccountQuotaEntity.setTotalQuota(userAccountPO.getTotalQuota());
        userAccountQuotaEntity.setSurplusQuota(userAccountPO.getSurplusQuota());
        userAccountQuotaEntity.setUserAccountStatusVO(UserAccountStatusVO.get(userAccountPO.getStatus()));
        userAccountQuotaEntity.genModelTypes(userAccountPO.getModelTypes());
        return userAccountQuotaEntity;
    }

    /**
     * Inserts the durable ledger row before the guarded account decrement. The
     * transaction is deliberately short and contains no model call. A private
     * rollback exception lets the public contract return {@code false} while
     * still rolling back the inserted RESERVED row when quota is unavailable.
     */
    @Override
    public boolean reserveChatQuota(String openid, String requestId) {
        return reserveChatQuota(openid, requestId, null);
    }

    @Override
    public boolean reserveChatQuota(String openid, String requestId, ChatBillingQuote quote) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return transaction.execute(status -> {
                ChatQuotaPO reservation = ChatQuotaPO.reserved(openid, requestId);
                if (quote != null) {
                    reservation.setModel(quote.getModel());
                    reservation.setInputPrice(quote.getInputPrice());
                    reservation.setCachedInputPrice(quote.getCachedInputPrice());
                    reservation.setOutputPrice(quote.getOutputPrice());
                }
                int inserted = chatQuotaDao.insertReservation(reservation);
                if (inserted != 1) {
                    throw new DataAccessResourceFailureException("Unable to record chat quota reservation");
                }
                // Preserve historical entitlements, then debit the wallet at a snapshotted price.
                if (chatQuotaDao.decrementUserQuota(openid) != 1) {
                    if (quote == null || chatQuotaDao.debitWallet(openid, quote.getReservedAmount()) != 1) {
                        throw new InsufficientChatQuotaException();
                    }
                    if (chatQuotaDao.recordWalletCharge(openid, requestId, quote.getReservedAmount()) != 1) {
                        throw new DataAccessResourceFailureException("Unable to snapshot chat charge");
                    }
                }
                return true;
            });
        } catch (InsufficientChatQuotaException e) {
            return false;
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Chat quota request already exists for openid and requestId", e);
        }
    }

    /**
     * The model call happens outside this transaction. Only the ledger state
     * transition and, for failures, the one-unit refund are performed here.
     */
    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public void settleChatQuota(String openid, String requestId, boolean success) {
        if (success) {
            chatQuotaDao.confirmReservation(openid, requestId);
            return;
        }

        int released = chatQuotaDao.releaseReservation(openid, requestId);
        if (released != 1) {
            return;
        }

        refund(chatQuotaDao.queryReservation(openid, requestId));
    }

    /**
     * Processes at most one bounded batch per call. Each row is conditionally
     * released before its refund, making retries and concurrent settlement
     * idempotent. The caller can schedule this short transaction periodically.
     */
    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public void releaseExpiredChatQuotas() {
        Date expiredBefore = new Date(System.currentTimeMillis()
                - TimeUnit.MINUTES.toMillis(RESERVATION_EXPIRY_MINUTES));
        List<ChatQuotaPO> reservations = chatQuotaDao.findExpiredReservations(
                expiredBefore,
                EXPIRED_RESERVATION_BATCH_SIZE
        );
        for (ChatQuotaPO reservation : reservations) {
            int released = chatQuotaDao.releaseReservationById(reservation.getId());
            if (released != 1) {
                continue;
            }
            refund(reservation);
        }
    }

    private void refund(ChatQuotaPO reservation) {
        if (reservation == null) throw new DataAccessResourceFailureException("Missing chat charge ledger");
        int refunded = reservation.getChargeAmount() == null
                ? chatQuotaDao.refundUserQuota(reservation.getOpenid())
                : chatQuotaDao.refundWallet(reservation.getOpenid(), reservation.getChargeAmount());
        if (refunded != 1) throw new DataAccessResourceFailureException("Unable to refund chat charge");
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public void settleChatUsage(String openid, String requestId, ChatUsage usage) {
        if (usage == null) throw new IllegalArgumentException("Token usage is required");
        ChatQuotaPO row = chatQuotaDao.queryReservation(openid, requestId);
        if (row == null) throw new DataAccessResourceFailureException("Missing chat charge ledger");
        if (row.getStatus() != ChatQuotaPO.RESERVED) return;
        row.setPromptTokens(usage.getPromptTokens());
        row.setCompletionTokens(usage.getCompletionTokens());
        row.setCachedPromptTokens(usage.getCachedPromptTokens());
        row.setStatus(ChatQuotaPO.CONFIRMED);
        row.setSettledAmount(BigDecimal.ZERO);
        if (row.getChargeAmount() != null) {
            BigDecimal actual = ChatBillingQuote.cost(usage.getPromptTokens(), usage.getCompletionTokens(),
                    usage.getCachedPromptTokens(), row.getInputPrice(), row.getCachedInputPrice(), row.getOutputPrice());
            row.setSettledAmount(actual);
            BigDecimal refund = row.getChargeAmount().subtract(actual);
            if (refund.signum() > 0 && chatQuotaDao.refundWallet(openid, refund) != 1)
                throw new DataAccessResourceFailureException("Unable to refund authorization difference");
            if (refund.signum() < 0 && chatQuotaDao.debitWallet(openid, refund.negate()) != 1) {
                row.setStatus(ChatQuotaPO.REVIEW);
                log.error("Chat charge requires review: requestId={}, actual={}, authorized={}",
                        requestId, actual, row.getChargeAmount());
            }
        }
        if (chatQuotaDao.recordSettlement(row) != 1)
            throw new DataAccessResourceFailureException("Unable to settle chat usage");
    }

    private static final class InsufficientChatQuotaException extends RuntimeException {
        private InsufficientChatQuotaException() {
            super("Insufficient chat quota");
        }
    }

}
