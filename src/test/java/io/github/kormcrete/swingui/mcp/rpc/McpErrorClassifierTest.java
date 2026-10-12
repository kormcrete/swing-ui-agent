package io.github.kormcrete.swingui.mcp.rpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpErrorClassifierTest {

    private final McpErrorClassifier classifier = new McpErrorClassifier();

    @Test
    void aMessageSayingNotFoundIsNotFound() {
        McpError e = classifier.classify(new IllegalStateException("order ORD-1640 not found"));
        assertThat(e.type()).isEqualTo(McpErrorType.NOT_FOUND);
        assertThat(e.message()).isEqualTo("order ORD-1640 not found");
    }

    @Test
    void anythingElseIsAnInputError() {
        assertThat(classifier.classify(new IllegalArgumentException("Missing required 'action' object")).type())
                .isEqualTo(McpErrorType.INPUT_ERROR);
        assertThat(classifier.classify(new NullPointerException()).message()).isEqualTo("java.lang.NullPointerException");
    }
}
