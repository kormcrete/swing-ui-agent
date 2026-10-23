package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.FixtureClass;
import io.github.kormcrete.swingui.testing.FixtureClassCondition;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class TestNamesTest {

    static final List<String> SEEN = Collections.synchronizedList(new ArrayList<String>());

    @FixtureClass
    static class Repeated {
        @RegisterExtension
        static final BeforeEachCallback NAMES = (ExtensionContext c) -> SEEN.add(TestNames.of(c));

        @Test void runs() { }

        @RepeatedTest(2) void places() { }
    }

    @Test
    void aMethodIsClassHashMethod() {
        assertThat(TestNames.of("io.github.kormcrete.orderdesk.Outer$Inner", "places",
                "[engine:junit-jupiter]/[class:io.github.kormcrete.orderdesk.Outer]/[nested-class:Inner]/[method:places()]"))
                .isEqualTo("Outer$Inner#places");
        assertThat(TestNames.of("PlaceOrderUiIT", "places", "[engine:junit-jupiter]/[class:PlaceOrderUiIT]/[method:places()]"))
                .isEqualTo("PlaceOrderUiIT#places");
    }

    @Test
    void aTemplateInvocationGetsItsIndex() {
        assertThat(TestNames.of("io.github.kormcrete.orderdesk.Outer$Inner", "places",
                "[engine:junit-jupiter]/[class:io.github.kormcrete.orderdesk.Outer]/[nested-class:Inner]"
                        + "/[test-template:places(int)]/[test-template-invocation:#2]"))
                .isEqualTo("Outer$Inner#places[2]");
    }

    @Test
    void aRecordedTestIsRecordedAndItsFileName() {
        assertThat(TestNames.recorded("io.github.kormcrete.orderdesk.RecordedUiIT", "place-order"))
                .isEqualTo("RecordedUiIT#recorded[place-order]");
    }

    @Test
    void aRunningTestIsNamedFromItsContext() {
        SEEN.clear();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(Repeated.class))
                .configurationParameter(FixtureClassCondition.PARAMETER, FixtureClassCondition.VALUE).build());

        assertThat(SEEN).containsExactlyInAnyOrder("TestNamesTest$Repeated#runs",
                "TestNamesTest$Repeated#places[1]", "TestNamesTest$Repeated#places[2]");
    }
}
