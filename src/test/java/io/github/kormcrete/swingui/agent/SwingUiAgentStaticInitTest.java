package io.github.kormcrete.swingui.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The apple.awt.UIElement property only works when set before the toolkit starts, so initialising the
 * agent class must start no toolkit. On JDK 1.8.0_291 macOS, the native {@code awt} library load
 * that actually starts AppKit (and reads the property) runs in the static init of both
 * {@code java.awt.Toolkit} itself and of plain AWT value classes such as {@code Color}/{@code Font}/
 * {@code Dimension} — constructing one of those loads {@code java.awt.Toolkit} as a side effect
 * without ever loading a concrete {@code sun.*Toolkit}. So the pattern matches either the bare
 * {@code java.awt.Toolkit} class or any concrete {@code sun.*Toolkit}. The child JVMs run headless,
 * so neither can activate anything. Java 25 no longer loads the toolkit from {@code Color}; CI saw it do so on 8 to 21.
 */
class SwingUiAgentStaticInitTest {

    private static final Pattern TOOLKIT_LOADED = Pattern.compile("\\b(?:sun\\.[\\w.$]*|java\\.awt\\.)Toolkit\\b");

    @Test
    void initialisingTheAgentStartsNoToolkit() throws Exception {
        String out = runProbe();
        assertThat(out).contains(SwingUiAgentStaticInitProbe.DONE);
        String after = out.substring(out.indexOf(SwingUiAgentStaticInitProbe.START));
        assertThat(TOOLKIT_LOADED.matcher(after).find())
                .as("a toolkit started while SwingUiAgent initialised:%n%s", after)
                .isFalse();
    }

    @Test
    void theProbeSeesAToolkitThatDoesStart() throws Exception {
        String out = runProbe("control");
        String after = out.substring(out.indexOf(SwingUiAgentStaticInitProbe.START));
        assertThat(TOOLKIT_LOADED.matcher(after).find()).isTrue();
    }

    @Test
    void theColorControlAlsoStartsAToolkit() throws Exception {
        assumeTrue(javaFeatureVersion() <= 21, "Color loads no toolkit on Java 25");
        String out = runProbe("color");
        String after = out.substring(out.indexOf(SwingUiAgentStaticInitProbe.START));
        assertThat(TOOLKIT_LOADED.matcher(after).find())
                .as("constructing java.awt.Color did not load a Toolkit class either:%n%s", after)
                .isTrue();
    }

    private static String runProbe(String... args) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-verbose:class", "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"),
                SwingUiAgentStaticInitProbe.class.getName()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = read(process.getInputStream());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(out).contains(SwingUiAgentStaticInitProbe.START);
        return out;
    }

    private static int javaFeatureVersion() {
        String spec = System.getProperty("java.specification.version");
        return Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
