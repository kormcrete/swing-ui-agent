package io.github.kormcrete.swingui.driver.platform;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class ModuleAccessTest {

    private static final boolean JAVA_8 = System.getProperty("java.version").startsWith("1.8");

    @Test
    void opensTheDesktopPackagesOnJava9AndLaterAndNothingOnJava8() {
        Instrumentation inst = mock(Instrumentation.class);
        assertThat(ModuleAccess.open(inst)).isNull();
        Collection<Invocation> calls = mockingDetails(inst).getInvocations();
        if (JAVA_8) {
            assertThat(calls).isEmpty();
            return;
        }
        assertThat(calls).hasSize(1);
        Invocation redefine = calls.iterator().next();
        assertThat(redefine.getMethod().getName()).isEqualTo("redefineModule");
        Map<?, ?> exports = (Map<?, ?>) redefine.getArguments()[2];
        Map<?, ?> opens = (Map<?, ?>) redefine.getArguments()[3];
        assertThat(exports.keySet()).contains("java.awt", "java.awt.peer", "java.awt.dnd", "java.awt.dnd.peer", "sun.awt");
        assertThat(opens).isEqualTo(exports);
    }

    @Test
    void aRefusedRedefineIsReportedNotThrown() {
        if (JAVA_8) return;
        Instrumentation inst = mock(Instrumentation.class, invocation -> {
            throw new UnsupportedOperationException("not modifiable");
        });
        assertThat(ModuleAccess.open(inst)).contains("cannot open java.desktop").contains("not modifiable");
    }
}
