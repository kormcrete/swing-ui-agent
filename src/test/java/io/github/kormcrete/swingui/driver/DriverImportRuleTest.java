package io.github.kormcrete.swingui.driver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The driver uses plain Swing and the JDK only: it imports nothing from the app, a library, or the rest of this project. */
class DriverImportRuleTest {

    private static final Path DRIVER_SOURCES = Paths.get("src/main/java/io/github/kormcrete/swingui/driver");
    private static final String OWN = "io.github.kormcrete.swingui.driver";
    /** Fully qualified names that must not appear even outside an import, e.g. in a reflective lookup. */
    private static final String[] FORBIDDEN = {
            "org.jdesktop.", "org.assertj.", "com.fasterxml.", "org.springframework."
    };
    private static final Pattern PROJECT_REFERENCE = Pattern.compile("io\\.github\\.kormcrete\\.(?!swingui\\.driver\\b)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)");

    @Test
    void driverSourcesReferenceOnlyTheJdkAndThemselves() throws IOException {
        Path root = DRIVER_SOURCES; // relative to the project root, where Maven runs the tests
        assertThat(root).isDirectory();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        List<String> offences = new ArrayList<String>();
        for (Path file : files) {
            String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String where = root.relativize(file).toString();
            Matcher m = IMPORT.matcher(src);
            while (m.find()) {
                if (offends(m.group(1))) offences.add(where + ": import " + m.group(1));
            }
            for (String prefix : FORBIDDEN) {
                if (src.contains(prefix)) offences.add(where + ": mentions " + prefix);
            }
            if (PROJECT_REFERENCE.matcher(src).find()) offences.add(where + ": mentions a class outside the driver");
            if (src.contains("getAccessibleContext(")) offences.add(where + ": getAccessibleContext()");
            if (src.contains("java.awt.Robot") || src.contains("new Robot(")) offences.add(where + ": java.awt.Robot");
        }
        assertThat(offences).isEmpty();
    }

    @Test
    void theRuleCatchesWhatItNames() {
        assertThat(offends("com.example.orders.OrdersFrame")).isTrue();
        assertThat(offends("io.github.kormcrete.swingui.flow.FlowJson")).isTrue();
        assertThat(offends("org.jdesktop.swingx.JXBusyLabel")).isTrue();
        assertThat(offends("com.fasterxml.jackson.databind.ObjectMapper")).isTrue();
        assertThat(offends("io.github.kormcrete.swingui.driver.model.Locator")).isFalse();
        assertThat(offends("javax.swing.JTable")).isFalse();
        assertThat(PROJECT_REFERENCE.matcher("io.github.kormcrete.swingui.driver.Edt").find()).isFalse();
        assertThat(PROJECT_REFERENCE.matcher("io.github.kormcrete.swingui.flow.X").find()).isTrue();
    }

    static boolean offends(String imported) {
        return !(imported.startsWith("java.") || imported.startsWith("javax.")
                || imported.equals(OWN) || imported.startsWith(OWN + "."));
    }
}
