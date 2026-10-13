package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.inspect.Inspector;
import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The person's steps go into the same recording as the agent's; replay, redo and delete work on it. */
class UiMcpInspectTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UiMcpReadToolsTest.DriverHost backend = new UiMcpReadToolsTest.DriverHost();
    private final UiMcpSession session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
    private Inspector.Sink sink;

    @BeforeEach
    void setUp() {
        session.startRecording(true);
        sink = session.humanSink();
    }

    @Test
    void withoutInspectorOnTheToolRefusesAndSaysHowToTurnItOn() throws Exception {
        DriverException e = catchThrowableOfType(() -> session.inspect(mapper.readTree("{\"op\":\"recording\"}")),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("inspector=on");
    }

    @Test
    void aStepThePersonDidIsRecordedWithoutRunningAgain() {
        Inspector.Result r = sink.done(step("click", Locator.ofId("save")));
        assertThat(r.ok).isTrue();
        assertThat(sink.steps()).containsExactly("click(#save)");
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void aStepExportCannotWriteIsNotRecordedAndSaysWhy() {
        Locator at = Locator.ofId("canvas");
        at.point = new LinkedHashMap<String, Double>();
        at.point.put("x", 0.5);
        at.point.put("y", 0.5);
        Inspector.Result r = sink.done(step("click", at));
        assertThat(r.ok).isFalse();
        assertThat(sink.steps()).isEmpty();
    }

    @Test
    void aCellCheckIsBoxedOnTheCellNotTheWholeTable() {
        Map<String, Object> s = step("expect", Locator.of("table", null));
        Map<String, Object> cell = new LinkedHashMap<String, Object>();
        cell.put("row", Collections.singletonMap("Order", "ORD-1640"));
        cell.put("column", "Status");
        cell.put("expected", "Open");
        s.put("cell", cell);
        assertThat(sink.done(s).ok).isTrue();
        Locator target = sink.target(0);
        assertThat(target.role).isEqualTo("table");
        assertThat(target.row).containsEntry("Order", "ORD-1640");
        assertThat(target.column).isEqualTo("Status");
    }

    @Test
    void replayRunsAStepAgainWithoutRecordingIt() {
        sink.done(step("click", Locator.ofId("save")));
        Inspector.Result r = sink.replay(0);
        assertThat(r.ok).isTrue();
        assertThat(backend.verbs.calls).anyMatch(c -> c.startsWith("click"));
        assertThat(sink.steps()).hasSize(1);
    }

    @Test
    void redoCutsTheRecordingAndDeleteDropsOneStep() {
        sink.done(step("click", Locator.ofId("new")));
        sink.done(step("click", Locator.ofId("save")));
        sink.done(step("click", Locator.ofId("delete")));
        sink.remove(1);
        assertThat(sink.steps()).containsExactly("click(#new)", "click(#delete)");
        sink.truncate(1);
        assertThat(sink.steps()).containsExactly("click(#new)");
    }

    @Test
    void aCheckThePersonChoseRunsAndIsRecordedWhenItPasses() {
        Map<String, Object> s = step("expect", Locator.ofId("status"));
        s.put("text", "saved");
        Inspector.Result r = sink.check(s);
        assertThat(r.ok).isTrue();
        assertThat(sink.steps()).hasSize(1);
        assertThat(backend.verbs.calls).anyMatch(c -> c.startsWith("expect"));
    }

    private static Map<String, Object> step(String action, Locator target) {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("action", action);
        s.put("target", target);
        return s;
    }

    @Test
    void thePersonsRecordStartsAFreshRecordingFromWhereTheAppIs() {
        sink.done(step("click", Locator.ofId("save")));
        sink.begin();
        assertThat(sink.steps()).isEmpty();
        assertThat(sink.done(step("click", Locator.ofId("new"))).ok).isTrue();
        assertThat(sink.steps()).containsExactly("click(#new)");
        assertThat(backend.verbs.calls).isEmpty();
    }
}
