package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.vocab.Matcher;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The matcher thread: jobs go to it, results come back under the session's lock, readers wait without the lock. */
class FoldingTest {

    private final Object session = new Object();
    private final List<Integer> applied = new CopyOnWriteArrayList<Integer>();
    private final List<Boolean> underLock = new CopyOnWriteArrayList<Boolean>();

    private Folding folding() {
        return new Folding(session, (job, result) -> {
            underLock.add(Thread.holdsLock(session));
            applied.add(job.rev);
        });
    }

    private static Folding.Job job(int rev) {
        return new Folding.Job(rev, Collections.<Matcher.Item>emptyList(), Collections.<Matcher.Candidate>emptyList(), 0);
    }

    @Test
    void aJobIsAppliedUnderTheSessionsLockAndAReaderCanWaitForIt() {
        Folding f = folding();

        f.schedule(job(1));
        f.settle(5000);

        assertThat(applied).containsExactly(1);
        assertThat(underLock).containsExactly(true);
    }

    @Test
    void aReaderHoldingTheLockDoesNotWaitForWhatOnlyTheLockCanApply() {
        Folding f = folding();

        synchronized (session) {
            f.schedule(job(2));
            long start = System.nanoTime();
            f.settle(5000);
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.MILLISECONDS.toNanos(500));
        }
        f.settle(5000);
        assertThat(applied).containsExactly(2);
    }

    @Test
    void aStaleResultThatLooksAgainIsWaitedForToo() {
        // as the session does when the recording changed while a job matched: the result is dropped, a new job goes
        List<Folding> self = new CopyOnWriteArrayList<Folding>();
        Folding f = new Folding(session, (job, result) -> {
            applied.add(job.rev);
            if (job.rev == 1) self.get(0).schedule(job(2));
        });
        self.add(f);

        long start = System.nanoTime();
        f.schedule(job(1));
        f.settle(5000);

        assertThat(applied).containsExactly(1, 2);
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(2));
    }

    @Test
    void aNewerJobReplacesOneNotStartedYet() {
        Folding f = folding();

        synchronized (session) {
            f.schedule(job(1));
            // the matcher thread cannot take job 2 between these two: they come under its own monitor
            synchronized (f) {
                f.schedule(job(2));
                f.schedule(job(3));
            }
        }
        f.settle(5000);

        // job 1 ran or was replaced too; job 2 never ran
        assertThat(applied).doesNotContain(2).endsWith(3);
    }

    @Test
    void anErrorWhileApplyingDoesNotStopFolding() {
        Folding f = new Folding(session, (job, result) -> {
            if (job.rev == 1) throw new AssertionError("broken");
            applied.add(job.rev);
        });

        f.schedule(job(1));
        f.settle(5000);
        f.schedule(job(2));
        long start = System.nanoTime();
        f.settle(5000);

        assertThat(applied).containsExactly(2);
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(2));
    }

    @Test
    void aMatchThatBreaksAppliesNothingAndFoldingGoesOn() {
        List<Matcher.Result> results = new CopyOnWriteArrayList<Matcher.Result>();
        Folding f = new Folding(session, (job, result) -> {
            applied.add(job.rev);
            if (result != null) results.add(result);
        });

        f.schedule(new Folding.Job(1, null, Collections.<Matcher.Candidate>emptyList(), 0));   // the match throws
        f.settle(5000);
        f.schedule(job(2));
        f.settle(5000);

        assertThat(applied).containsExactly(1, 2);
        assertThat(results).hasSize(1);
    }

    @Test
    void anAskedMatchRunsOnADeepStackAndThrowsWhatItThrows() {
        assertThat(Folding.onMatcherStack(() -> Thread.currentThread().getName())).isEqualTo("swing-ui-agent folding (asked)");
        IllegalArgumentException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> Folding.onMatcherStack(() -> { throw new IllegalArgumentException("no"); }),
                IllegalArgumentException.class);
        assertThat(e).hasMessage("no");
    }
}
