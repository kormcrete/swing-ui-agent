package io.github.kormcrete.swingui.driver.model;

/**
 * One step of a locator's {@code scope}: {@code tab:<title>}, {@code section:<caption>} or
 * {@code id:<name>}. An id step means "strictly inside a component whose {@code getName()} is
 * {@code <name>}" - never that component itself, and never a section id a source contributed. Only a
 * caller writes id steps ({@code Target.byId(x)} used as a container, or an MCP target's scope);
 * {@code Trail} and the recorder never derive one. A tab or section title that ends in {@code *} matches every title
 * that starts with the rest ({@code tab:New order*}), for a title that ends with data such as the customer.
 */
public final class ScopeStep {

    public enum Kind { TAB, SECTION, ID }

    public final Kind kind;
    public final String title;

    private ScopeStep(Kind kind, String title) {
        this.kind = kind;
        this.title = title;
    }

    public static ScopeStep parse(String step) {
        if (step != null && step.startsWith("tab:")) return new ScopeStep(Kind.TAB, step.substring(4));
        if (step != null && step.startsWith("section:")) return new ScopeStep(Kind.SECTION, step.substring(8));
        if (step != null && step.startsWith("id:")) return new ScopeStep(Kind.ID, step.substring(3));
        throw Locator.bad("scope step must be tab:<title>, section:<caption> or id:<name>, was '" + step + "'");
    }

    /** {@code title} must be resolved already: an untitled tab contributes no scope step at all. */
    public static String tab(String title) {
        if (title == null) throw Locator.bad("a tab scope step needs a title; omit the step for an untitled tab");
        return "tab:" + title;
    }

    public static String section(String caption) {
        return "section:" + caption;
    }

    public static String id(String name) {
        return "id:" + name;
    }
}
