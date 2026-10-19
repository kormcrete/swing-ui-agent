package io.github.kormcrete.swingui.driver.settle;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The rules for "this dialog reports an error" that plugins added; they add up: any rule that says so decides. None
 * by default: how an app shows an unexpected error is its own business, and many apps use an error-type message for
 * a refused input that a test answers on purpose. EDT.
 */
public final class ErrorDialogs implements ErrorDialogClassifier {

    /**
     * A ready-made rule for an app whose error-type option panes ({@link JOptionPane#ERROR_MESSAGE}) only ever report
     * errors: {@code driver.addErrorDialogClassifier(ErrorDialogs.ERROR_OPTION_PANE)}.
     */
    public static final ErrorDialogClassifier ERROR_OPTION_PANE = ErrorDialogs::hasErrorOptionPane;

    private final List<ErrorDialogClassifier> rules = new CopyOnWriteArrayList<ErrorDialogClassifier>();

    /** Adds a rule; null is ignored. */
    public void add(ErrorDialogClassifier rule) {
        if (rule != null) rules.add(rule);
    }

    @Override
    public boolean isError(Window dialog) {
        for (ErrorDialogClassifier rule : rules) {
            if (rule.isError(dialog)) return true;
        }
        return false;
    }

    /** Whether {@code c} is, or holds, an option pane of the error type. */
    public static boolean hasErrorOptionPane(Component c) {
        if (c instanceof JOptionPane && ((JOptionPane) c).getMessageType() == JOptionPane.ERROR_MESSAGE) return true;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                if (hasErrorOptionPane(child)) return true;
            }
        }
        return false;
    }
}
