package io.github.kormcrete.swingui.flow;

/** Switches the logged-in user. {@code as} names a UiUsers alias -- never a password. */
public class LoginAsAction extends Action {
    public String as;

    @Override public String describe() { return "loginAs(" + as + ")"; }
}
