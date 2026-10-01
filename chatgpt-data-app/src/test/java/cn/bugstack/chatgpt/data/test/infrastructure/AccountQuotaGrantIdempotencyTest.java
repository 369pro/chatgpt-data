package cn.bugstack.chatgpt.data.test.infrastructure;

import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustQuotaEntity;
import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.AccountRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountQuotaGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountQuotaGrantPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AccountQuotaGrantIdempotencyTest {

    private AccountRepository repository;
    private IAccountQuotaGrantDao grantDao;
    private IUserAccountDao accountDao;

    @Before
    public void setUp() {
        repository = new AccountRepository();
        grantDao = mock(IAccountQuotaGrantDao.class);
        accountDao = mock(IUserAccountDao.class);
        ReflectionTestUtils.setField(repository, "accountQuotaGrantDao", grantDao);
        ReflectionTestUtils.setField(repository, "userAccountDao", accountDao);
        ReflectionTestUtils.setField(repository, "modelTypes", "deepseek-flash");
    }

    @Test
    public void newGrantUpdatesAccountOnce() {
        when(grantDao.insertIgnore(any())).thenReturn(1);
        when(accountDao.addOrCreateAccountQuota(any())).thenReturn(1);
        when(accountDao.queryUserAccount("user-1")).thenReturn(account(13, 13));

        assertEquals(Integer.valueOf(13), repository.adjustQuota(request(3)).getTotalQuota());

        verify(accountDao).addOrCreateAccountQuota(any());
    }

    @Test
    public void duplicateGrantReturnsCurrentAccountWithoutUpdating() {
        when(grantDao.insertIgnore(any())).thenReturn(0);
        when(grantDao.query("user-1", "award-order-1"))
                .thenReturn(AccountQuotaGrantPO.builder()
                        .openid("user-1")
                        .requestId("award-order-1")
                        .increaseQuota(3)
                        .build());
        when(accountDao.queryUserAccount("user-1")).thenReturn(account(13, 13));

        assertEquals(Integer.valueOf(13), repository.adjustQuota(request(3)).getTotalQuota());

        verify(accountDao, never()).addOrCreateAccountQuota(any());
    }

    @Test
    public void reusedRequestIdWithDifferentAmountIsRejected() {
        when(grantDao.insertIgnore(any())).thenReturn(0);
        when(grantDao.query("user-1", "award-order-1"))
                .thenReturn(AccountQuotaGrantPO.builder()
                        .openid("user-1")
                        .requestId("award-order-1")
                        .increaseQuota(3)
                        .build());

        try {
            repository.adjustQuota(request(4));
            fail("Expected request key reuse with a different amount to fail");
        } catch (IllegalArgumentException expected) {
            // The same award request must never be repurposed for another grant.
        }
    }

    @Test
    public void invalidGrantIsRejectedBeforeLedgerInsert() {
        try {
            repository.adjustQuota(AdjustQuotaEntity.builder()
                    .openid("user-1")
                    .requestId("award-order-1")
                    .increaseQuota(0)
                    .build());
            fail("Expected non-positive grants to be rejected");
        } catch (IllegalArgumentException expected) {
            // Validation must happen before INSERT IGNORE can coerce or mask the request.
        }

        verify(grantDao, never()).insertIgnore(any());
    }

    @Test
    public void overlongGrantKeysAreRejectedBeforeLedgerInsert() {
        try {
            repository.adjustQuota(AdjustQuotaEntity.builder()
                    .openid("u".repeat(65))
                    .requestId("award-order-1")
                    .increaseQuota(1)
                    .build());
            fail("Expected overlong openid to be rejected");
        } catch (IllegalArgumentException expected) {
            // The DB unique key must never see a truncated identity.
        }

        try {
            repository.adjustQuota(AdjustQuotaEntity.builder()
                    .openid("user-1")
                    .requestId("r".repeat(129))
                    .increaseQuota(1)
                    .build());
            fail("Expected overlong requestId to be rejected");
        } catch (IllegalArgumentException expected) {
            // The request id must fit the binary-collated ledger key exactly.
        }

        verify(grantDao, never()).insertIgnore(any());
    }

    private AdjustQuotaEntity request(int amount) {
        return AdjustQuotaEntity.builder()
                .openid("user-1")
                .requestId("award-order-1")
                .increaseQuota(amount)
                .build();
    }

    private UserAccountPO account(int total, int surplus) {
        return UserAccountPO.builder()
                .openid("user-1")
                .totalQuota(total)
                .surplusQuota(surplus)
                .build();
    }
}
