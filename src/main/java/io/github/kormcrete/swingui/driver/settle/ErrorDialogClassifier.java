package io.github.kormcrete.swingui.driver.settle;

import java.awt.Window;

/** One rule for "this dialog reports an error"; a plugin adds its app's ({@code Driver.addErrorDialogClassifier}). EDT. */
public interface ErrorDialogClassifier {

    ErrorDialogClassifier NONE = dialog -> false;

    boolean isError(Window dialog);
}
