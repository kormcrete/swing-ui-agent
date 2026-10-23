package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.junit.ClientApp;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StepCallVerbsTest {

    private final RecordingVerbs verbs = new RecordingVerbs();
    private final List<StepCall> ended = new ArrayList<StepCall>();
    private final List<Long> startedAt = new ArrayList<Long>();
    private final List<String> secrets = new ArrayList<String>();
    private final List<String> appCalls = new ArrayList<String>();

    private final StepCallVerbs.Sink sink = new StepCallVerbs.Sink() {
        @Override public void ended(StepCall call, long startedAtMillis) {
            ended.add(call);
            startedAt.add(startedAtMillis);
        }
        @Override public void secret(String value) {
            secrets.add(value);
        }
    };

    private final ClientApp app = new ClientApp() {
        @Override public void navigate(String target) { appCalls.add("navigate " + target); }
        @Override public void closeActiveTab() { appCalls.add("close"); }
        @Override public void loginAs(String alias) { appCalls.add("loginAs " + alias); }
        @Override public void loginAs(String user, String password) { appCalls.add("loginAs " + user + " " + password); }
        @Override public void relogin() { appCalls.add("relogin"); }
        @Override public <T> T hooks(Class<T> type) { return null; }
        @Override public String secret(String alias, String name) { return "s3cret-" + name; }
    };

    private static Expectation text(String text) {
        Expectation e = new Expectation();
        e.text = text;
        return e;
    }

    @Test
    void aSinkGetsEachStepAsItEndsAndTheListStaysEmpty() {
        StepCallVerbs kept = new StepCallVerbs(verbs, sink);
        long before = System.currentTimeMillis();
        kept.click(Locator.of("button", "Add to cart"), null, null, null, null);
        DriverException failure = new DriverException(Reason.EXPECTATION, "expected text '€7.32' but was '€0.00'");
        verbs.failNext = failure;

        Throwable thrown = catchThrowable(() -> kept.expect(Locator.of("label", "Total"), text("€7.32"), null));

        assertThat(thrown).isSameAs(failure);
        assertThat(ended).hasSize(2);
        assertThat(ended.get(0).json).isEqualTo("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}");
        assertThat(ended.get(0).passed).isTrue();
        assertThat(ended.get(1).json).startsWith("{\"action\":\"expect\"");
        assertThat(ended.get(1).passed).isFalse();
        assertThat(ended.get(1).message).isEqualTo(failure.getMessage());
        assertThat(ended.get(1).reason).isEqualTo(Reason.EXPECTATION);
        assertThat(ended.get(1).ms).isGreaterThanOrEqualTo(0);
        assertThat(startedAt.get(0)).isBetween(before, System.currentTimeMillis());
        assertThat(startedAt.get(1)).isGreaterThanOrEqualTo(startedAt.get(0));
        assertThat(kept.calls()).isEmpty();
    }

    @Test
    void aSinkThatThrowsNeverHidesTheStepsFailure() {
        StepCallVerbs kept = new StepCallVerbs(verbs, new StepCallVerbs.Sink() {
            @Override public void ended(StepCall call, long startedAtMillis) {
                throw new IllegalStateException("the journal broke");
            }
        });
        DriverException failure = new DriverException(Reason.NOT_FOUND, "no button 'Place order'");
        verbs.failNext = failure;

        Throwable thrown = catchThrowable(() -> kept.click(Locator.of("button", "Place order"), null, null, null, null));
        kept.click(Locator.of("button", "Add to cart"), null, null, null, null);

        assertThat(thrown).isSameAs(failure);
        assertThat(verbs.calls).hasSize(2);
    }

    @Test
    void inSinkModeALoginWithAPasswordIsKeptWithoutIt() {
        StepCallVerbs kept = new StepCallVerbs(verbs, sink);
        ClientApp kApp = kept.app(app);

        kApp.loginAs("ada.shop", "pa55word");
        kApp.relogin();

        assertThat(appCalls).containsExactly("loginAs ada.shop pa55word", "relogin");
        assertThat(ended).hasSize(2);
        assertThat(ended.get(0).json).isEqualTo("{\"action\":\"loginAs\",\"user\":\"ada.shop\"}");
        assertThat(ended.get(1).json).isEqualTo("{\"action\":\"relogin\"}");
        assertThat(secrets).containsExactly("pa55word");
    }

    @Test
    void withoutASinkALoginWithAPasswordIsNotKept() {
        StepCallVerbs kept = new StepCallVerbs(verbs);
        ClientApp kApp = kept.app(app);

        kApp.loginAs("ada.shop", "pa55word");
        kApp.relogin();
        kApp.loginAs("ada");

        assertThat(appCalls).containsExactly("loginAs ada.shop pa55word", "relogin", "loginAs ada");
        assertThat(kept.calls()).hasSize(1);
        assertThat(kept.calls().get(0).json).isEqualTo("{\"action\":\"loginAs\",\"as\":\"ada\"}");
    }

    @Test
    void aSecretReadThroughTheAppReachesTheSink() {
        StepCallVerbs kept = new StepCallVerbs(verbs, sink);

        String read = kept.app(app).secret("ada", "certPassword");
        kept.type(Locator.of("text", "Note"), read, null, null, null);

        assertThat(read).isEqualTo("s3cret-certPassword");
        assertThat(secrets).containsExactly("s3cret-certPassword");
        assertThat(ended.get(0).json).isEqualTo("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},\"text\":\"***\"}");
    }
}
