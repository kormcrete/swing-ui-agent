package io.github.kormcrete.swingui.flow;

public class NavigateAction extends Action {
    public String target;
    @Override public String describe() { return "navigate(" + target + ")"; }
}
