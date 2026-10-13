package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseTest {

    private long now = 1_000_000L;
    private final Lease lease = new Lease(() -> now);

    @Test
    void aCallWithoutATokenRunsWhileNobodyHoldsTheLease() {
        assertThatCode(() -> {
            lease.enter(null);
            lease.exit(null);
        }).doesNotThrowAnyException();
        assertThat(lease.holder()).isNull();
    }

    @Test
    void aCallWithoutATokenIsBusyWhileSomeoneHoldsIt() {
        lease.acquire("ReleaseGateSuite (run 7f3)", null);
        String since = new SimpleDateFormat("HH:mm:ss").format(new Date(now));
        assertThatThrownBy(() -> lease.enter(null))
                .isInstanceOf(DriverException.class)
                .hasMessage("BUSY: busy: leased by ReleaseGateSuite (run 7f3) since " + since);
    }

    @Test
    void theHolderCallsWithItsToken() {
        Lease.Grant g = lease.acquire("t", null);
        lease.enter(g.token);
        lease.exit(g.token);
        assertThat(lease.holder().owner).isEqualTo("t");
    }

    @Test
    void anotherOwnerIsBusyWhileTheLeaseIsInUse() {
        Lease.Grant g = lease.acquire("a", null);
        lease.enter(g.token);
        now += 60_000L;
        assertThatThrownBy(() -> lease.acquire("b", null)).hasMessageContaining("busy: leased by a");
    }

    @Test
    void anotherOwnerTakesOverAnIdleLease() {
        lease.acquire("stopped test JVM", null);
        now += Lease.TAKEOVER_IDLE_MS;
        Lease.Grant b = lease.acquire("rerun", null);
        assertThat(lease.holder().token).isEqualTo(b.token);
    }

    @Test
    void noTakeoverWhileACallRuns() {
        Lease.Grant a = lease.acquire("a", null);
        lease.enter(a.token);
        now += 10 * Lease.TAKEOVER_IDLE_MS;
        assertThatThrownBy(() -> lease.acquire("b", null)).hasMessageContaining("busy: leased by a");
        lease.exit(a.token);
    }

    @Test
    void theLeaseEndsAfterItsTtlWithoutACall() {
        lease.acquire("a", 5_000L);
        now += 5_000L;
        assertThat(lease.holder()).isNull();
        lease.enter(null);
    }

    @Test
    void aRunningCallKeepsTheLeasePastItsTtl() {
        Lease.Grant a = lease.acquire("a", 5_000L);
        lease.enter(a.token);
        now += 50_000L;
        assertThat(lease.holder()).isNotNull();
        lease.exit(a.token);
        now += 4_999L;
        assertThat(lease.holder()).isNotNull();
    }

    @Test
    void unknownTokenSaysTheClientMayHaveRestarted() {
        assertThatThrownBy(() -> lease.enter("abc"))
                .isInstanceOf(DriverException.class)
                .hasMessage("BUSY: lease abc is not held (released, expired after its ttl, or the client restarted)");
    }

    @Test
    void releaseFreesItAndAnUnknownTokenIsANoOp() {
        Lease.Grant a = lease.acquire("a", null);
        assertThat(lease.release("nope")).isFalse();
        assertThat(lease.release(a.token)).isTrue();
        assertThat(lease.holder()).isNull();
    }

    @Test
    void anOwnerIsRequired() {
        assertThatThrownBy(() -> lease.acquire(" ", null))
                .isInstanceOf(DriverException.class)
                .extracting(e -> ((DriverException) e).reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void aRunningCallWithoutATokenBlocksAcquireUntilItExits() {
        lease.enter(null);
        assertThatThrownBy(() -> lease.acquire("b", null))
                .isInstanceOf(DriverException.class)
                .hasMessage("BUSY: busy: a call without a lease is running");
        lease.exit(null);
        assertThat(lease.acquire("b", null)).isNotNull();
    }

    @Test
    void anUnpairedExitWithoutATokenNeverGoesNegative() {
        lease.exit(null);
        lease.enter(null);
        assertThatThrownBy(() -> lease.acquire("b", null)).hasMessageContaining("without a lease");
    }

    @Test
    void aRenewKeepsALeaseFromTakeover() {
        long t0 = now;
        Lease.Grant g = lease.acquire("UiRun A", null);
        now = t0 + 14_000;
        assertThat(lease.renewHeld(g.token)).isTrue();
        now = t0 + 20_000; // 20 s after the lease, 6 s after the renew: no takeover yet
        assertThatThrownBy(() -> lease.acquire("UiRun B", null))
                .isInstanceOf(DriverException.class).hasMessageContaining("leased by UiRun A");
        now = t0 + 29_001; // 15 s after the renew
        assertThat(lease.acquire("UiRun B", null).owner).isEqualTo("UiRun B");
    }

    @Test
    void aRenewOfAForeignReleasedOrMissingTokenIsFalse() {
        Lease.Grant g = lease.acquire("UiRun A", null);
        assertThat(lease.renewHeld("not-the-token")).isFalse();
        assertThat(lease.renewHeld(null)).isFalse();
        lease.release(g.token);
        assertThat(lease.renewHeld(g.token)).isFalse();
    }

    @Test
    void noTestRunLeasesTheAgentWhileAPersonIsBusyInTheApp() {
        String[] reason = {"a person is recording in the app"};
        lease.blockedBy(() -> reason[0]);
        assertThatThrownBy(() -> lease.acquire("suite", null))
                .isInstanceOf(DriverException.class)
                .hasMessage("BUSY: busy: a person is recording in the app");
        reason[0] = null;
        assertThat(lease.acquire("suite", null).owner).isEqualTo("suite");
    }

    @Test
    void thePalettehearsWhenATestRunTakesTheAgentAndWhenItEnds() {
        java.util.List<Boolean> heard = new java.util.ArrayList<Boolean>();
        lease.watchedBy(heard::add);
        Lease.Grant g = lease.acquire("suite", 1000L);
        lease.release(g.token);
        lease.acquire("suite", 1000L);
        now += 2000;
        assertThat(lease.holder()).isNull();
        assertThat(heard).containsExactly(true, false, true, false);
    }
}
