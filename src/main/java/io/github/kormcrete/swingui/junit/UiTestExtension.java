package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.Verbs;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Binds {@link Client}s to running apps: one per Client parameter of the test method, each leased,
 * reset and bound; all released after the test. A failure writes evidence (tree, screenshot, the client's EDT stack
 * on a timeout) under the test module's target/ui-failures, one {@code client-N} folder per client when there are
 * several.
 */
public final class UiTestExtension implements ParameterResolver, TestExecutionExceptionHandler, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(UiTestExtension.class);
    private static final String JVM_RUN = runId();

    private final java.util.function.Function<ExtensionContext, UiPool> pools;

    public UiTestExtension() {
        this(c -> UiPool.forSpec(c.getConfigurationParameter(UiPool.AGENTS_PARAMETER).orElse(UiPool.defaultSpec())));
    }

    /** The pool each test reserves from; tests pass {@link UiPool#of} over in-JVM agents. */
    UiTestExtension(java.util.function.Function<ExtensionContext, UiPool> pools) {
        this.pools = pools;
    }

    @Override
    public boolean supportsParameter(ParameterContext p, ExtensionContext c) {
        return p.getParameter().getType() == Client.class && c.getTestMethod().isPresent();
    }

    @Override
    public Object resolveParameter(ParameterContext p, ExtensionContext c) {
        ExtensionContext.Store store = c.getStore(NS);
        // An @AfterEach(Client) after a failed begin: the same failure again, with no new lease, reset or evidence.
        ParameterResolutionException failed = store.get("beginFailure", ParameterResolutionException.class);
        if (failed != null) throw failed;
        @SuppressWarnings("unchecked")
        List<Client> clients = store.get("clients", List.class);
        if (clients == null) clients = begin(c, store);
        @SuppressWarnings("unchecked")
        List<String> aliases = store.get("aliases", List.class);
        return pick(clients, aliases, p, c);
    }

    /**
     * Reserves and leases every client the test method asks for, whichever parameter JUnit resolves first.
     * A begin that fails part-way leaves its begun runs in the store: afterEach ends them and releases the pool.
     */
    private List<Client> begin(ExtensionContext c, ExtensionContext.Store store) {
        List<UiRun> runs = new ArrayList<UiRun>();
        store.put("runs", runs);
        try {
            List<String> aliases = aliases(c);
            store.put("aliases", aliases);
            UiPool pool = pools.apply(c);
            List<UiAgents> reserved = pool.reserve(aliases.size(), owner(c));
            store.put("pool", pool);
            store.put("reserved", reserved);
            List<Client> clients = new ArrayList<Client>();
            for (int i = 0; i < reserved.size(); i++) {   // ascending port order
                UiRun run = new UiRun(reserved.get(i).shared(), owner(c), screenshotRoot(c.getRequiredTestClass()));
                runs.add(run);
                clients.add(run.begin(aliases.get(i), runId()));   // its own runId
            }
            store.put("clients", clients);
            return clients;
        } catch (RuntimeException e) {
            Path dir = evidence(c, e);
            ParameterResolutionException failure =
                    new ParameterResolutionException(e.getMessage() + "\nUI evidence: " + dir.toAbsolutePath(), e);
            store.put("beginFailure", failure);
            throw failure;
        }
    }

    /** One alias per client, in test-method order: @As, else the test's @LoginAs (null: the default user). */
    static List<String> aliases(ExtensionContext c) {
        String fallback = alias(c);
        List<String> out = new ArrayList<String>();
        for (java.lang.reflect.Parameter p : c.getRequiredTestMethod().getParameters()) {
            if (p.getType() != Client.class) continue;
            As as = p.getAnnotation(As.class);
            out.add(as != null ? as.value() : fallback);
        }
        if (out.isEmpty()) out.add(fallback); // only a lifecycle method asks
        return out;
    }

    private static Client pick(List<Client> clients, List<String> aliases, ParameterContext p, ExtensionContext c) {
        java.lang.reflect.Executable owner = p.getDeclaringExecutable();
        As as = p.getParameter().getAnnotation(As.class);
        if (!owner.equals(c.getRequiredTestMethod()) && as != null) {
            int i = aliases.indexOf(as.value());
            if (i < 0) {
                throw new ParameterResolutionException("@As(\"" + as.value() + "\") on " + owner.getName()
                        + " names no client of " + c.getRequiredTestMethod().getName() + " (its aliases: " + aliases + ")");
            }
            return clients.get(i);
        }
        int position = 0;
        java.lang.reflect.Parameter[] params = owner.getParameters();
        for (int i = 0; i < p.getIndex(); i++) if (params[i].getType() == Client.class) position++;
        if (position >= clients.size()) {
            throw new ParameterResolutionException(owner.getName() + " takes Client parameter " + (position + 1)
                    + ", but " + c.getRequiredTestMethod().getName() + " has only " + clients.size() + " client(s)");
        }
        return clients.get(position);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext c, Throwable t) throws Throwable {
        if (t instanceof org.opentest4j.TestAbortedException) throw t; // an abort is no failure: no evidence
        if (c.getStore(NS).get("evidence") == null) {
            Path dir = evidence(c, t);
            t.addSuppressed(new UiEvidence("UI evidence: " + dir.toAbsolutePath()));
        }
        throw t;
    }

    /** Warnings, then every run ended (last first), then the pool released: each step runs even if one before it threw. */
    @Override
    public void afterEach(ExtensionContext c) {
        ExtensionContext.Store store = c.getStore(NS);
        try {
            try {
                @SuppressWarnings("unchecked")
                List<Client> clients = store.get("clients", List.class);
                if (clients != null) {
                    for (int i = 0; i < clients.size(); i++) {
                        String who = clients.size() > 1 ? " [client " + (i + 1) + "]" : "";
                        for (String w : clients.get(i).warnings()) {
                            System.out.println("[ui-warning] " + c.getDisplayName() + who + ": " + w);
                        }
                    }
                }
            } finally {
                @SuppressWarnings("unchecked")
                List<UiRun> runs = store.get("runs", List.class);
                if (runs != null) endAll(c, runs);
            }
        } finally {
            UiPool pool = store.get("pool", UiPool.class);
            @SuppressWarnings("unchecked")
            List<UiAgents> reserved = store.get("reserved", List.class);
            if (pool != null) pool.release(reserved);
        }
    }

    /** Ends each run, last first; one that throws does not keep the others leased. Rethrows the first failure. */
    private static void endAll(ExtensionContext c, List<UiRun> runs) {
        RuntimeException first = null;
        for (int i = runs.size() - 1; i >= 0; i--) {
            UiRun run = runs.get(i);
            try {
                String lost = run.leaseLost();
                if (lost != null) {
                    String who = runs.size() > 1 ? " [client " + (i + 1) + "]" : "";
                    System.out.println("[ui-warning] " + c.getDisplayName() + who + ": " + lost);
                }
            } catch (RuntimeException e) {
                first = keep(first, e);
            }
            try {
                run.end();
            } catch (RuntimeException e) {
                first = keep(first, e);
            }
        }
        if (first != null) throw first;
    }

    private static RuntimeException keep(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static Path evidence(ExtensionContext c, Throwable t) {
        @SuppressWarnings("unchecked")
        List<UiRun> runs = c.getStore(NS).get("runs", List.class);
        Path dir = evidenceRoot(c.getRequiredTestClass()).resolve(c.getRequiredTestClass().getSimpleName())
                .resolve(c.getTestMethod().map(Method::getName).orElse("class"));
        @SuppressWarnings("unchecked")
        List<String> aliases = c.getStore(NS).get("aliases", List.class);
        int clients = aliases != null ? aliases.size() : runs == null ? 0 : runs.size();
        Path written;
        if (clients <= 1) {
            UiRun run = runs == null || runs.isEmpty() ? null : runs.get(0);
            Verbs verbs = run == null ? null : run.verbs();
            Supplier<String> edt = run == null ? () -> "EDT stack: no client" : run::edtStack;
            written = Evidence.write(dir, verbs, t, edt);
        } else {
            for (int i = 0; i < clients; i++) {   // one folder per client, begun or not
                Path sub = dir.resolve("client-" + (i + 1));
                if (runs != null && i < runs.size()) {
                    UiRun run = runs.get(i);
                    Evidence.write(sub, run.verbs(), t, run::edtStack);
                } else {
                    writeNotBegun(sub, i + 1, t);
                }
            }
            written = dir;
        }
        c.getStore(NS).put("evidence", written);
        return written;
    }

    private static void writeNotBegun(Path sub, int n, Throwable t) {
        try {
            java.nio.file.Files.createDirectories(sub);
            String text = "client " + n + " was not begun: the test failed before its run started.\n"
                    + "failure: " + t + "\n";
            java.nio.file.Files.write(sub.resolve("evidence.txt"), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            System.out.println("[ui-warning] could not write evidence for client " + n + ": " + e);
        }
    }

    /** The test module's target/ui-failures, not the working directory's: an IDE may run from the project root. */
    static Path evidenceRoot(Class<?> testClass) {
        return ModuleTarget.of(testClass).resolve("ui-failures");
    }

    /** The test module's target/ui-screenshots: where {@link Client#saveScreenshot} puts a relative path. */
    static Path screenshotRoot(Class<?> testClass) {
        return ModuleTarget.of(testClass).resolve("ui-screenshots");
    }

    static String alias(ExtensionContext c) {
        LoginAs m = c.getTestMethod().map(x -> x.getAnnotation(LoginAs.class)).orElse(null);
        if (m != null) return m.value();
        LoginAs k = c.getRequiredTestClass().getAnnotation(LoginAs.class);
        return k == null ? null : k.value();
    }

    static String owner(ExtensionContext c) {
        return c.getRequiredTestClass().getSimpleName() + "#" + c.getTestMethod().map(Method::getName).orElse("class")
                + " (" + JVM_RUN + ")";
    }

    static String runId() {
        return "UIT-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                + "-" + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }

    /** Carries the evidence path in a failure's suppressed list; no stack of its own. */
    static final class UiEvidence extends RuntimeException {
        UiEvidence(String message) {
            super(message, null, false, false);
        }
    }
}
