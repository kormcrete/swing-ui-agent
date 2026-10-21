package io.github.kormcrete.swingui.flow;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The run report's text. */
class FlowReportTest {

    @Test
    void resolvedValuesAreShownOnTheStepLine() {
        Flow flow = FlowJson.parse("{\"name\":\"copy-order\",\"steps\":["
                + "{\"action\":\"click\",\"id\":\"copy\",\"target\":{\"role\":\"button\",\"name\":\"Copy\"}},"
                + "{\"action\":\"type\",\"say\":\"Type the note\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},"
                + "\"text\":\"Copy ${run.id} of ${copy.rows.Orders.added[0].Number}\"}]}");
        FlowResult result = new FlowResult();
        result.steps.add(StepResult.pass(0, "click(button 'Copy')"));
        StepResult typed = StepResult.pass(1, "type(text 'Note' 'Copy a1b2c3d4 of ORD-1643')");
        typed.resolved = new LinkedHashMap<String, String>();
        typed.resolved.put("${run.id}", "a1b2c3d4");
        typed.resolved.put("${copy.rows.Orders.added[0].Number}", "ORD-1643");
        result.steps.add(typed);

        String report = FlowReport.format(flow, result);

        assertThat(report).contains("[1] PASS click(button 'Copy') ");
        assertThat(report).doesNotContain("click(button 'Copy') (");
        assertThat(report).contains("[2] PASS Type the note (run.id = a1b2c3d4, copy.rows.Orders.added[0].Number = ORD-1643) ");
        assertThat(report).contains("type(text 'Note' 'Copy a1b2c3d4 of ORD-1643')");
    }
}
