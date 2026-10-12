package io.github.kormcrete.swingui.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowJsonTest {

    @Test
    void mapperIsConfiguredAndIgnoresUnknownFields() throws Exception {
        ObjectMapper mapper = FlowJson.mapper();
        // Unknown property must not throw — flows evolve over time.
        Object parsed = mapper.readValue("{\"unknown\":123}", Object.class);
        assertThat(parsed).isNotNull();
    }

    /**
     * A {@code FlowJson.NonNullMixin} registered only for a base class also reaches an unregistered subclass:
     * Jackson resolves class-level annotations along the type hierarchy, checking each level for a mixin.
     * Pinned on a throwaway pair, since {@code Action} carries {@code @JsonInclude} itself and would hide the mixin.
     */
    @Test
    void aMixinRegisteredOnlyForABaseClassAlsoGovernsAnUnregisteredSubclass() throws Exception {
        ObjectMapper local = new ObjectMapper();
        local.addMixIn(MixinExperimentBase.class, FlowJson.NonNullMixin.class);

        MixinExperimentSub sub = new MixinExperimentSub();
        sub.kept = "k";
        // sub.dropped stays null and is declared only on the SUBCLASS, which never got its own mixin.

        JsonNode node = local.readTree(local.writeValueAsString(sub));

        assertThat(node.has("kept")).isTrue();
        assertThat(node.has("dropped")).isFalse();
    }

    /** The mixed-in base, outside the {@code Action} hierarchy; its subclass gets no mixin of its own. */
    private static class MixinExperimentBase {}

    private static class MixinExperimentSub extends MixinExperimentBase {
        public String kept;
        public String dropped;
    }
}
