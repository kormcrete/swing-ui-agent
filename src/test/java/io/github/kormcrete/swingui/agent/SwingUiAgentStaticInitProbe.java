package io.github.kormcrete.swingui.agent;

/**
 * Run in a child JVM by SwingUiAgentStaticInitTest. {@code control} starts the toolkit on purpose by
 * asking for it directly; {@code color} starts it too, but only as a side effect of constructing a
 * plain AWT value class ({@code java.awt.Color}) — on JDK 1.8.0_291 macOS, both {@code Toolkit}'s and
 * these value classes' static init load the native {@code awt} library that starts AppKit.
 */
public final class SwingUiAgentStaticInitProbe {

    static final String START = "LAUNCHER-PROBE-START";
    static final String DONE = "LAUNCHER-PROBE-DONE";

    private SwingUiAgentStaticInitProbe() {}

    public static void main(String[] args) throws Exception {
        System.out.println(START);
        if (args.length > 0 && "control".equals(args[0])) {
            java.awt.Toolkit.getDefaultToolkit();
        } else if (args.length > 0 && "color".equals(args[0])) {
            new java.awt.Color(0);
        } else {
            Class.forName("io.github.kormcrete.swingui.agent.SwingUiAgent", true,
                    SwingUiAgentStaticInitProbe.class.getClassLoader());
        }
        System.out.println(DONE);
    }
}
