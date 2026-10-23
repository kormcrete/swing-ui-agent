package io.github.kormcrete.swingui.junit;

import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test's id, one rule for the run report and the run journal: {@code <Class>#<method>}, {@code <Class>} the binary
 * class name after its last {@code .} ({@code Outer$Inner}), with {@code [<n>]} appended for the n-th invocation of a
 * test template (a repeated or parameterized test).
 */
final class TestNames {

    private static final Pattern INVOCATION = Pattern.compile("\\[test-template-invocation:#(\\d+)]$");

    private TestNames() {}

    /** The id of the test method {@code c} runs. */
    static String of(ExtensionContext c) {
        return of(c.getRequiredTestClass().getName(), c.getRequiredTestMethod().getName(), c.getUniqueId());
    }

    /** The id of {@code method} of the class of binary name {@code className}; {@code uniqueId} is JUnit's. */
    static String of(String className, String method, String uniqueId) {
        String id = className.substring(className.lastIndexOf('.') + 1) + "#" + method;
        Matcher m = INVOCATION.matcher(uniqueId == null ? "" : uniqueId);
        return m.find() ? id + "[" + m.group(1) + "]" : id;
    }
}
