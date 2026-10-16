package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.vocab.Matcher;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The matcher thread: jobs go to it, results come back under the session's lock, readers can wait for them. */
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
        f.settle(5000, false);

        assertThat(applied).containsExactly(1);
        assertThat(underLock).containsExactly(true);
    }

    @Test
    void aReaderHoldingTheLockAppliesTheResultItself() {
        Folding f = folding();

        synchronized (session) {
            f.schedule(job(2));
            f.settle(5000, true);
            // applied here, by this thread, while the matcher thread waits for the lock
            assertThat(applied).containsExactly(2);
        }
        f.settle(5000, false);
        assertThat(applied).containsExactly(2);
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
        f.settle(5000, false);

        // job 1 ran or was replaced too; job 2 never ran
        assertThat(applied).doesNotContain(2).endsWith(3);
    }
}
