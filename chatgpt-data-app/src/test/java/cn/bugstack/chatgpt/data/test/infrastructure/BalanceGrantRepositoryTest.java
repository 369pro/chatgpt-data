package cn.bugstack.chatgpt.data.test.infrastructure;

import cn.bugstack.chatgpt.data.domain.account.model.entity.AdjustBalanceEntity;
import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;
import cn.bugstack.chatgpt.data.domain.account.service.balance.BalanceGrantService;
import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.BalanceRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountWalletDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IBalanceGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AccountWalletPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.BalanceGrantPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Focused tests for grant idempotency and bounded monetary validation.
 */
public class BalanceGrantRepositoryTest {

    private IUserAccountDao userAccountDao;
    private IBalanceGrantDao balanceGrantDao;
    private IAccountWalletDao accountWalletDao;
    private BalanceRepository repository;

    @Before
    public void setUp() {
        userAccountDao = mock(IUserAccountDao.class);
        balanceGrantDao = mock(IBalanceGrantDao.class);
        accountWalletDao = mock(IAccountWalletDao.class);
        repository = new BalanceRepository(userAccountDao, balanceGrantDao, accountWalletDao);
        when(userAccountDao.queryUserAccount("user-1")).thenReturn(UserAccountPO.builder().openid("user-1").build());
    }

    @Test
    public void duplicateRequestDoesNotApplyWalletTwice() {
        AdjustBalanceEntity request = request("user-1", "grant-1", "1.25");
        AccountWalletPO wallet = wallet("user-1", "1.25", "1.25");
        when(balanceGrantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(1);
        when(accountWalletDao.addOrCreateWallet(any(AccountWalletPO.class))).thenReturn(1);
        when(accountWalletDao.queryWalletForUpdate("user-1")).thenReturn(wallet);

        AccountBalanceVO first = repository.adjustBalance(request);

        when(balanceGrantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(0);
        when(balanceGrantDao.query("big-market", "grant-1"))
                .thenReturn(BalanceGrantPO.builder().appId("big-market").requestId("grant-1")
                        .openid("user-1").amount(new BigDecimal("1.25")).source("lottery").build());
        AccountBalanceVO retry = repository.adjustBalance(request);

        assertEquals(0, first.getAvailableAmount().compareTo(new BigDecimal("1.25")));
        assertEquals(0, retry.getTotalAmount().compareTo(new BigDecimal("1.25")));
        verify(accountWalletDao).addOrCreateWallet(any(AccountWalletPO.class));
    }

    @Test
    public void requestIdCannotChangeAccountOrAmount() {
        when(userAccountDao.queryUserAccount("user-2"))
                .thenReturn(UserAccountPO.builder().openid("user-2").build());
        when(balanceGrantDao.insertIgnore(any(BalanceGrantPO.class))).thenReturn(0);
        when(balanceGrantDao.query("big-market", "grant-1"))
                .thenReturn(BalanceGrantPO.builder().appId("big-market").requestId("grant-1")
                        .openid("user-1").amount(new BigDecimal("1.25")).source("lottery").build());

        try {
            repository.adjustBalance(request("user-1", "grant-1", "1.26"));
            fail("same request ID with a different amount must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            repository.adjustBalance(request("user-2", "grant-1", "1.25"));
            fail("same request ID with a different account must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        verify(accountWalletDao, never()).addOrCreateWallet(any(AccountWalletPO.class));
    }

    @Test
    public void serviceRejectsInvalidGrantAmountsBeforePersistence() {
        BalanceGrantService service = new BalanceGrantService(repository);
        for (String value : new String[]{"0", "-1", "0.000000001", "1000000.00000001"}) {
            try {
                service.adjustBalance(request("user-1", "grant-" + value, value));
                fail("invalid amount accepted: " + value);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        verify(balanceGrantDao, never()).insertIgnore(any(BalanceGrantPO.class));
    }

    private static AdjustBalanceEntity request(String openid, String requestId, String amount) {
        return AdjustBalanceEntity.builder().appId("big-market").openid(openid).requestId(requestId)
                .increaseAmount(new BigDecimal(amount)).source("lottery").build();
    }

    private static AccountWalletPO wallet(String openid, String available, String total) {
        return AccountWalletPO.builder().openid(openid).currency("CNY")
                .availableAmount(new BigDecimal(available)).totalAmount(new BigDecimal(total)).build();
    }
}
