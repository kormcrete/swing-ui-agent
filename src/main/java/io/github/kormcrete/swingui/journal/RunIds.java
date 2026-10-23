package io.github.kormcrete.swingui.journal;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Run ids, the names a run folder or a checkpoint may take, and test names as file names. */
public final class RunIds {

    /** This JVM's run id, e.g. {@code UIT-20261023-081204-a3f9}: made once, when the class loads. */
    public static final String JVM = next();

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]+");

    private RunIds() {}

    /** A new id of the run id format {@code UIT-yyyyMMdd-HHmmss-xxxx} (local time, four random hex digits). */
    public static String next() {
        return "UIT-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                + "-" + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }

    /** True when {@code name} uses only {@code A-Z a-z 0-9 . _ -} and is not {@code .} or {@code ..}. */
    public static boolean isSafeName(String name) {
        return name != null && SAFE.matcher(name).matches() && !".".equals(name) && !"..".equals(name);
    }

    /** {@code test} as a file name every OS takes: each of {@code <>:"/\|?*} and each control character becomes {@code _}. */
    public static String fileName(String test) {
        StringBuilder sb = new StringBuilder(test.length());
        for (int i = 0; i < test.length(); i++) {
            char c = test.charAt(i);
            sb.append("<>:\"/\\|?*".indexOf(c) >= 0 || Character.isISOControl(c) ? '_' : c);
        }
        return sb.toString();
    }
}
