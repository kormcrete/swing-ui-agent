package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.util.Locale;
import java.util.function.Supplier;

/** Picks the seam for this JVM. Only macOS on Java 8 has one (MacJdk8Seam). */
public final class Seams {

    private Seams() {}

    public static Seam forThisJvm() {
        return select(System.getProperty("os.name", ""), System.getProperty("java.version", ""), MacJdk8Seam::new);
    }

    public static Seam select(String osName, String javaVersion, Supplier<Seam> macJdk8) {
        String os = osName.toLowerCase(Locale.ROOT);
        boolean java8 = javaVersion.startsWith("1.8");
        if (os.startsWith("mac") && java8) {
            return macJdk8.get();
        }
        throw new DriverException(Reason.CAPABILITY_GAP, "swing-ui-agent runs on macOS with Java 8; " + osName
                + " on Java " + javaVersion + " is not supported yet");
    }
}
