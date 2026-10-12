package io.github.kormcrete.swingui.driver.settle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A worker thread stands in for the event dispatch thread. */
class EdtErrorsTest {

    private final List<Throwable> seenByPrevious = new ArrayList<Throwable>();

    private Throwable crash(boolean record) throws Exception {
        final Thread worker = new Thread(() -> {
            throw new RuntimeException("boom");
        });
        worker.setUncaughtExceptionHandler((t, e) -> seenByPrevious.add(e));
        EdtErrors errors = new EdtErrors(() -> worker);
        errors.ensureInstalled();
        if (record) errors.begin();
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        List<Throwable> caught = errors.end();
        return caught.isEmpty() ? null : caught.get(0);
    }

    @Test
    void anExceptionDuringAnActionIsRecordedAndStillHandled() throws Exception {
        Throwable caught = crash(true);
        assertThat(caught).hasMessage("boom");
        assertThat(seenByPrevious).hasSize(1);
    }

    @Test
    void anExceptionOutsideAnActionIsOnlyHandled() throws Exception {
        assertThat(crash(false)).isNull();
        assertThat(seenByPrevious).hasSize(1);
    }
}
