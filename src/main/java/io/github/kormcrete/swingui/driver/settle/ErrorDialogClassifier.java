package io.github.kormcrete.swingui.driver.settle;

import java.awt.Window;

/** The app's rule for "this dialog reports an error". EDT. */
public interface ErrorDialogClassifier {

    ErrorDialogClassifier NONE = dialog -> false;

    boolean isError(Window dialog);
}
