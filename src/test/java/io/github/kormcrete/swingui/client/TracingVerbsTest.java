package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.UiActionFailed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static io.github.kormcrete.swingui.junit.UiAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TracingVerbsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TracingVerbs verbs = new TracingVerbs();
    private final Client ui = new Client(verbs, verbs.app(), "trace");

    @Test
    void aClickBecomesOneClickStep() {
        ui.button("Add to cart").click();

        assertThat(trace()).isEqualTo(json("[{'action':'click','target':{'role':'button','name':'Add to cart'}}]"));
        assertThat(verbs.derivable()).isTrue();
        assertThat(verbs.notDerivable()).isNull();
    }

    @Test
    void aCellTypeKeepsRowColumnAndText() {
        ui.tab("New order · Ada Shop Ltd").table().row("Product", "Gel pen, black").cell("Qty").type("10", null, true);

        assertThat(trace()).isEqualTo(json("[{'action':'type','target':{'scope':['tab:New order · Ada Shop Ltd'],"
                + "'role':'table','row':{'Product':'Gel pen, black'},'column':'Qty'},'text':'10','commit':true}]"));
    }

    @Test
    void aReadMarksTheTraceNotDerivable() {
        ui.button("Add to cart").click();

        Throwable read = catchThrowable(() -> ui.label("Cart total").text());

        assertThat(read).isInstanceOf(UiActionFailed.class);
        assertThat(verbs.derivable()).isFalse();
        assertThat(verbs.notDerivable()).isEqualTo("the body reads the app (read), which a trace cannot answer");
        assertThat(verbs.steps()).hasSize(1);
    }

    @Test
    void anExpectIsKeptAsAStepButMarksTheTraceNotDerivable() {
        assertThat(ui.label("Cart total")).hasText("€17.94");

        assertThat(trace().get(0).get("action").asText()).isEqualTo("expect");
        assertThat(verbs.derivable()).isFalse();
        assertThat(verbs.notDerivable()).isEqualTo("the body checks the app (expect)");
    }

    @Test
    void appStepsAreStepsToo() {
        ui.navigate("Orders");
        ui.closeActiveTab();
        ui.loginAs("clerk");

        assertThat(trace()).isEqualTo(json("[{'action':'navigate','target':'Orders'},{'action':'close'},"
                + "{'action':'loginAs','as':'clerk'}]"));
        assertThat(verbs.derivable()).isTrue();
    }

    @Test
    void hooksHaveNoStepForm() {
        Throwable hooks = catchThrowable(() -> ui.hooks(Runnable.class));

        assertThat(hooks).isNotNull();
        assertThat(verbs.notDerivable()).isEqualTo("the body calls the app plugin's hooks, which have no step form");
    }

    @Test
    void aBodyThatNeverEndsStopsAtTheStepLimit() {
        Throwable endless = catchThrowable(() -> {
            while (true) ui.button("+").click();
        });

        assertThat(endless).isInstanceOf(UiActionFailed.class);
        assertThat(verbs.steps()).hasSize(TracingVerbs.MAX_STEPS);
        assertThat(verbs.notDerivable()).isEqualTo("the body made more than " + TracingVerbs.MAX_STEPS + " steps");
    }

    private JsonNode trace() {
        return JSON.valueToTree(verbs.steps());
    }

    private static JsonNode json(String singleQuoted) {
        try {
            return JSON.readTree(singleQuoted.replace('\'', '"'));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
