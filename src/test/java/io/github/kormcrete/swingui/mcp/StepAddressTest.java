package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class StepAddressTest {

    @Test
    void anIntegerOrAStringNamesAStep() {
        StepAddress a = StepAddress.parse(IntNode.valueOf(7));
        assertThat(a.step).isEqualTo(7);
        assertThat(a.check).isEqualTo(0);
        assertThat(a.isCheck()).isFalse();
        assertThat(a.toString()).isEqualTo("7");
        assertThat(StepAddress.parse(TextNode.valueOf("7")).toString()).isEqualTo("7");
    }

    @Test
    void aDottedStringNamesACheck() {
        StepAddress a = StepAddress.parse(TextNode.valueOf("7.2"));
        assertThat(a.step).isEqualTo(7);
        assertThat(a.check).isEqualTo(2);
        assertThat(a.isCheck()).isTrue();
        assertThat(a.toString()).isEqualTo("7.2");
    }

    @Test
    void anythingElseIsRefused() {
        for (com.fasterxml.jackson.databind.JsonNode bad : new com.fasterxml.jackson.databind.JsonNode[] {
                TextNode.valueOf("7.0"), TextNode.valueOf("x"), IntNode.valueOf(0), IntNode.valueOf(-1),
                TextNode.valueOf("7.2.1"), TextNode.valueOf(""), JsonNodeFactory.instance.booleanNode(true),
                JsonNodeFactory.instance.numberNode(1.5), JsonNodeFactory.instance.nullNode(), null}) {
            DriverException e = catchThrowableOfType(() -> StepAddress.parse(bad), DriverException.class);
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).contains("a step number (7) or a check label (\"7.2\")");
        }
    }
}
