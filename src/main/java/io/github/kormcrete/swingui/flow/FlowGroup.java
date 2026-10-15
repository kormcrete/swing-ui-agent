package io.github.kormcrete.swingui.flow;

/** A run of whole steps with one sentence: flat record indexes {@code from} to {@code to}, inclusive, on step boundaries. */
public final class FlowGroup {
    public final int from;
    public final int to;
    public final String say;

    public FlowGroup(int from, int to, String say) {
        this.from = from;
        this.to = to;
        this.say = say;
    }

    @Override public String toString() {
        return "FlowGroup[" + from + ".." + to + " '" + say + "']";
    }
}
