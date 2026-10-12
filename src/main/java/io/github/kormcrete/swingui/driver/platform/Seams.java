package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.util.function.Function;

/** Picks the seam for this JVM: AwtSeam on macOS, Windows and Linux (X11), Java 8 and later. */
public final class Seams {

    /** The Java versions CI runs the suite on. A later one gets the seam too; its self-check decides. */
    public static final String VERIFIED_JAVA = "8, 11, 17, 21 and 25";

    private Seams() {}

    public static Seam forThisJvm() {
        return select(System.getProperty("os.name", ""), System.getProperty("java.version", ""), AwtSeam::new);
    }

    public static Seam select(String osName, String javaVersion, Function<Platform, Seam> seam) {
        Platform platform = Platform.of(osName);
        if (platform != null) {
            return seam.apply(platform);
        }
        throw new DriverException(Reason.CAPABILITY_GAP, "swing-ui-agent runs on macOS, Windows and Linux; " + osName
                + " on Java " + javaVersion + " is not supported yet");
    }
}
