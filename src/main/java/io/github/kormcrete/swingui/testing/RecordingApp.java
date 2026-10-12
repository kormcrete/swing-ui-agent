package io.github.kormcrete.swingui.testing;

import io.github.kormcrete.swingui.junit.ClientApp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A ClientApp that records app calls into a call list (shared with a RecordingVerbs, if given). No AWT. */
public final class RecordingApp implements ClientApp {
    public final List<String> calls;
    private final Map<Class<?>, Object> hooks = new HashMap<Class<?>, Object>();

    public RecordingApp() { this(new ArrayList<String>()); }
    public RecordingApp(List<String> calls) { this.calls = calls; }

    public <T> RecordingApp register(Class<T> type, T impl) {
        hooks.put(type, impl);
        return this;
    }

    @Override public void navigate(String target) { calls.add("hook navigate " + target); }
    @Override public void closeActiveTab() { calls.add("hook closeActiveTab"); }
    @Override public void loginAs(String alias) { calls.add("app loginAs " + alias); }
    @Override public void loginAs(String user, String password) { calls.add("app loginAs " + user + "/" + password); }
    @Override public void relogin() { calls.add("app relogin"); }

    @Override public <T> T hooks(Class<T> type) {
        Object h = hooks.get(type);
        if (h == null) throw new UnsupportedOperationException("no hooks registered for " + type.getName());
        return type.cast(h);
    }
}
