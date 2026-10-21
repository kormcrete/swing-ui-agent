package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.driver.outcome.UiReading;
import io.github.kormcrete.swingui.testing.RecordingApp;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OutcomeTest {

    /** The outcome of the design's example: Copy made order ORD-1641. */
    private static final String COPY = "{"
            + "\"opened\":{\"windows\":[\"Order ORD-1641\"],\"dialogs\":[],\"tabs\":[\"ORD-1641\"]},"
            + "\"closed\":{\"windows\":[],\"dialogs\":[\"Copy order\"],\"tabs\":[]},"
            + "\"fields\":{\"Number\":\"ORD-1641\",\"Status\":\"New\"},"
            + "\"texts\":{\"Total\":\"42.10 EUR\"},"
            + "\"rows\":{\"Orders\":{\"added\":[{\"Number\":\"ORD-1641\",\"Customer\":\"Ada Lovelace\"}],"
            + "\"removed\":[],\"changed\":[],\"count\":1641}},"
            + "\"items\":{\"Categories\":{\"added\":[\"Gel pens\"],\"removed\":[]}},"
            + "\"selected\":{\"Orders\":[{\"Number\":\"ORD-1641\",\"Customer\":\"Ada Lovelace\"}]},"
            + "\"message\":\"Order copied\"}";

    private static Outcome copy() throws Exception {
        return new Outcome(new ObjectMapper().readTree(COPY));
    }

    @Test
    void readAndTextOverTheDesignExample() throws Exception {
        Outcome o = copy();

        assertThat(o.text("$.rows.Orders.added[0].Number")).isEqualTo("ORD-1641");
        assertThat(o.text("$.rows['Orders'].added[-1]['Customer']")).isEqualTo("Ada Lovelace");
        assertThat(o.text("$.fields.Number")).isEqualTo("ORD-1641");
        assertThat(o.text("$.opened.tabs[0]")).isEqualTo("ORD-1641");
        assertThat(o.text("$.rows.Orders.count")).isEqualTo("1641");
        assertThat(o.text("$.message")).isEqualTo("Order copied");

        List<JsonNode> numbers = o.read("$..Number");
        assertThat(numbers).extracting(JsonNode::asText).containsExactly("ORD-1641", "ORD-1641", "ORD-1641");
        assertThat(o.read("$.rows.Orders.added[?(@.Customer == 'Ada Lovelace')]")).hasSize(1);
        assertThat(o.read("$.rows.Nothing")).isEmpty();
        assertThat(o.json().path("texts").path("Total").asText()).isEqualTo("42.10 EUR");
        // json() is a copy: the outcome stays what the step changed
        ((com.fasterxml.jackson.databind.node.ObjectNode) o.json()).remove("fields");
        assertThat(o.text("$.fields.Status")).isEqualTo("New");
    }

    @Test
    void textOfSeveralValuesFails() throws Exception {
        Outcome o = copy();

        UiActionFailed several = catchThrowableOfType(() -> o.text("$..Number"), UiActionFailed.class);
        assertThat(several.getMessage()).contains("$..Number", "3 values");
        UiActionFailed none = catchThrowableOfType(() -> o.text("$.rows.Orders.added[1].Number"), UiActionFailed.class);
        assertThat(none.getMessage()).contains("$.rows.Orders.added[1].Number", "no value");
        UiActionFailed object = catchThrowableOfType(() -> o.text("$.rows.Orders.added[0]"), UiActionFailed.class);
        assertThat(object.getMessage()).contains("$.rows.Orders.added[0]", "not one value");
        assertThatThrownBy(() -> o.read("rows.Orders")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("$");
    }

    // ---- Client.outcomeOf

    private static UiReading before() {
        return UiReading.builder().table("Orders", Arrays.asList("Number", "Customer"),
                Collections.singletonList(Arrays.asList("ORD-1640", "Ada Lovelace"))).build();
    }

    private static UiReading after() {
        return UiReading.builder().table("Orders", Arrays.asList("Number", "Customer"),
                Arrays.asList(Arrays.asList("ORD-1640", "Ada Lovelace"), Arrays.asList("ORD-1641", "Ada Lovelace"))).build();
    }

    /** Readings before Copy is clicked, and after. */
    private static Supplier<UiReading> copied(RecordingVerbs verbs) {
        return () -> verbs.calls.stream().anyMatch(c -> c.startsWith("click") && c.contains("Copy")) ? after() : before();
    }

    @Test
    void outcomeOfReadsWhatTheOneActionChanged() {
        RecordingVerbs verbs = new RecordingVerbs();
        verbs.readings = copied(verbs);
        Client ui = new Client(verbs, new RecordingApp(verbs.calls), "t");

        Outcome o = ui.outcomeOf(() -> ui.button("Copy").click());

        assertThat(o.text("$.rows.Orders.added[0].Number")).isEqualTo("ORD-1641");
        assertThat(o.read("$.rows.Orders.count").get(0).asInt()).isEqualTo(2);
        assertThat(verbs.calls).containsExactly("readUi", "click button 'Copy' null null null null", "readUi");
        // an action outside outcomeOf reads nothing
        ui.button("Save").click();
        assertThat(verbs.calls).hasSize(4);
    }

    @Test
    void outcomeOfTakesExactlyOneAction() {
        RecordingVerbs verbs = new RecordingVerbs();
        verbs.readings = copied(verbs);
        Client ui = new Client(verbs, new RecordingApp(verbs.calls), "t");

        assertThatThrownBy(() -> ui.outcomeOf(() -> { })).isInstanceOf(IllegalStateException.class)
                .hasMessage("outcomeOf takes exactly one action");
        assertThatThrownBy(() -> ui.outcomeOf(() -> {
            ui.button("Copy").click();
            ui.button("Save").click();
        })).isInstanceOf(IllegalStateException.class).hasMessage("outcomeOf takes exactly one action");
        // the second action never ran
        assertThat(verbs.calls).noneMatch(c -> c.contains("Save"));
        // a client made from it (another wait) shares it, and afterwards nothing is asked for any more
        Outcome o = ui.outcomeOf(() -> ui.timeout(java.time.Duration.ofSeconds(2)).menu("Order", "Copy"));
        assertThat(o.json().isObject()).isTrue();
        int calls = verbs.calls.size();
        ui.button("Save").click();
        assertThat(verbs.calls).hasSize(calls + 1);
    }

    @Test
    void aClientThatCannotReadTheUiFailsPlainly() {
        RecordingVerbs verbs = new RecordingVerbs();   // no readings: it cannot read the UI
        Client ui = new Client(verbs, new RecordingApp(verbs.calls), "t");

        UiActionFailed e = catchThrowableOfType(() -> ui.outcomeOf(() -> ui.button("Copy").click()), UiActionFailed.class);
        assertThat(e.getMessage()).contains("click", "cannot read");
    }

    @Test
    void anOutcomeTheVerbsDoNotGiveFailsPlainly() {
        // a Verbs that runs the action but gives no changes, as an older agent would
        RecordingVerbs recording = new RecordingVerbs();
        Verbs verbs = new Verbs() {
            @Override public CapabilityReport capabilities() { return recording.capabilities(); }
            @Override public Watch watch() { return recording.watch(); }
            @Override public Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows) {
                return recording.snapshot(window, ref, maxNodes, maxRows);
            }
            @Override public Node read(Locator target, Integer maxRows) { return recording.read(target, maxRows); }
            @Override public VerbOutcome click(Locator t, String button, Integer count, List<String> mods, Long timeout) {
                return recording.click(t, button, count, mods, timeout);
            }
            @Override public VerbOutcome withChanges(Supplier<VerbOutcome> action) { return action.get(); }
        };
        Client ui = new Client(verbs, new RecordingApp(recording.calls), "t");

        UiActionFailed e = catchThrowableOfType(() -> ui.outcomeOf(() -> ui.button("Copy").click()), UiActionFailed.class);
        assertThat(e.getMessage()).contains("what it changed could not be read");
    }

    // ---- Client.run and Target.selectedText / shownText

    @Test
    void runValuesAreOnePerClient() {
        RecordingVerbs verbs = new RecordingVerbs();
        Client ui = new Client(verbs, new RecordingApp(verbs.calls), "t");
        RunInfo run = ui.run();

        assertThat(run.id()).matches("[a-z0-9]{8}");
        assertThat(run.date()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(run.time()).matches("\\d{2}:\\d{2}:\\d{2}");
        assertThat(ui.run()).isSameAs(run);
        assertThat(ui.timeout(java.time.Duration.ofSeconds(1)).run()).isSameAs(run);
        assertThat(new Client(verbs, new RecordingApp(verbs.calls), "t").run().id()).isNotEqualTo(run.id());
        assertThat(ui.runId()).isEqualTo("t");
    }

    @Test
    void selectedAndShownTextReadAsARememberStepDoes() {
        RecordingVerbs verbs = new RecordingVerbs();
        verbs.rememberAnswer = "ORD-1641";
        Client ui = new Client(verbs, new RecordingApp(verbs.calls), "t");

        assertThat(ui.table("Orders").cell("Number").selectedText()).isEqualTo("ORD-1641");
        assertThat(ui.field("Number").timeout(java.time.Duration.ofSeconds(3)).shownText()).isEqualTo("ORD-1641");
        assertThat(verbs.calls).containsExactly(
                "remember table 'Orders' column=Number true null",
                "remember text 'Number' false 3000");

        verbs.failNext = new io.github.kormcrete.swingui.driver.DriverException(
                io.github.kormcrete.swingui.driver.Reason.EXPECTATION, "nothing to remember: label is empty");
        UiActionFailed e = catchThrowableOfType(() -> ui.label("Total").shownText(), UiActionFailed.class);
        assertThat(e.getMessage()).contains("nothing to remember");
    }
}
