package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.util.Locale;
import java.util.function.Supplier;

/** Picks the seam for this JVM. Only macOS has one (MacSeam), for Java 8 and later. */
public final class Seams {

    /** The Java versions CI runs the suite on. A later one gets the seam too; its self-check decides. */
    public static final String VERIFIED_JAVA = "8, 11, 17, 21 and 25";

    private Seams() {}

    public static Seam forThisJvm() {
        return select(System.getProperty("os.name", ""), System.getProperty("java.version", ""), MacSeam::new);
    }

    public static Seam select(String osName, String javaVersion, Supplier<Seam> mac) {
        if (osName.toLowerCase(Locale.ROOT).startsWith("mac")) {
            return mac.get();
        }
        throw new DriverException(Reason.CAPABILITY_GAP, "swing-ui-agent runs on macOS; " + osName
                + " on Java " + javaVersion + " is not supported yet");
    }
}
