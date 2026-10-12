package io.github.kormcrete.swingui.agent;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PluginWatchTest {

    @Test
    void firesOnceForTheWatchedClassWithItsLoaderThenUnregisters() {
        Instrumentation inst = mock(Instrumentation.class);
        PluginWatch watch = new PluginWatch(inst);
        RecordingHost host = new RecordingHost();
        watch.watch("com.example.orders.AppContext", host.host);
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[0]);

        watch.fire("com/example/orders/Other", loader);
        assertThat(host.loaders).isEmpty();
        watch.fire("com/example/orders/AppContext", loader);
        watch.fire("com/example/orders/AppContext", loader);

        assertThat(host.loaders).containsExactly(loader);
        assertThat(watch.isEmpty()).isTrue();
        verify(inst).removeTransformer(watch);
    }

    @Test
    void neverChangesClassBytes() throws Exception {
        PluginWatch watch = new PluginWatch(mock(Instrumentation.class));
        assertThat(watch.transform(null, "com/example/orders/Any", null, null, new byte[] {1, 2})).isNull();
    }

    @Test
    void theBootLoaderIsReportedAsTheSystemLoader() {
        PluginWatch watch = new PluginWatch(mock(Instrumentation.class));
        RecordingHost host = new RecordingHost();
        watch.watch("com.example.orders.AppContext", host.host);
        watch.fire("com/example/orders/AppContext", null);
        assertThat(host.loaders).containsExactly(ClassLoader.getSystemClassLoader());
    }

    /** A PluginHost whose appClassLoaded only records. */
    static final class RecordingHost {
        final java.util.List<ClassLoader> loaders = new java.util.ArrayList<ClassLoader>();
        final PluginHost host = new PluginHost(null, new PluginSlot("p", null), new PluginDescriptor(null, "com.example.orders.AppContext", "p")) {
            @Override public void appClassLoaded(ClassLoader appLoader) { loaders.add(appLoader); }
        };
    }
}
