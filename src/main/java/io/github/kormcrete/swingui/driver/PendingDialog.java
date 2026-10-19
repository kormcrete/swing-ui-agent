package io.github.kormcrete.swingui.driver;

import java.util.ArrayList;
import java.util.List;

/** A modal dialog an action left waiting: the next step has to deal with it. */
public final class PendingDialog {

    public String title;
    /** What it says, one line per line it shows. */
    public String message;
    public List<String> buttons = new ArrayList<String>();
    public List<String> fields = new ArrayList<String>();

    public PendingDialog() {}

    public PendingDialog(String title, String message, List<String> buttons, List<String> fields) {
        this.title = title;
        this.message = message;
        this.buttons = buttons;
        this.fields = fields;
    }

    /** One line: the title, the message with its lines joined by spaces, and the buttons. */
    public String summary() {
        return (title == null || title.isEmpty() ? "" : title + ": ") + String.valueOf(message).replace('\n', ' ') + " " + buttons;
    }
}
