package io.github.kormcrete.swingui.driver.model;

/**
 * A locator's {@code window}: {@code main}, {@code popup}, {@code dialog} (the modal nothing
 * blocks, else the only dialog showing), {@code dialog:<title>} or {@code window:<title>}. A title ending in
 * {@code *} matches a prefix, one starting with {@code *} a suffix, and {@code *text*} any title containing the
 * text -- for a title that leads with data, such as {@code "ORD-1640 - Order details"}.
 */
public final class WindowRef {

    public enum Kind { MAIN, DIALOG, POPUP, WINDOW }

    public final Kind kind;
    /** Null: any title. */
    public final String title;

    private WindowRef(Kind kind, String title) {
        this.kind = kind;
        this.title = title;
    }

    public static WindowRef parse(String token) {
        if ("main".equals(token)) return new WindowRef(Kind.MAIN, null);
        if ("popup".equals(token)) return new WindowRef(Kind.POPUP, null);
        if ("dialog".equals(token)) return new WindowRef(Kind.DIALOG, null);
        if (token != null && token.startsWith("dialog:")) return new WindowRef(Kind.DIALOG, token.substring(7));
        if (token != null && token.startsWith("window:")) return new WindowRef(Kind.WINDOW, token.substring(7));
        throw Locator.bad("window must be main, popup, dialog, dialog:<title> or window:<title>, was '" + token + "'");
    }

    public boolean titleMatches(String actual) {
        if (title == null) return true;
        String a = actual == null ? "" : actual;
        boolean lead = title.startsWith("*");
        boolean trail = title.endsWith("*") && title.length() > 1;
        String core = title.substring(lead ? 1 : 0, title.length() - (trail ? 1 : 0));
        if (lead && trail) return a.contains(core);
        if (lead) return a.endsWith(core);
        if (trail) return a.startsWith(core);
        return a.equals(title);
    }
}
