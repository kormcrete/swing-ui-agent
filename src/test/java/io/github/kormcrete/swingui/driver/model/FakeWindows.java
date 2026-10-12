package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import io.github.kormcrete.swingui.driver.window.WindowSource;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/** Window roots whose contents are unshown panels. */
class FakeWindows implements WindowSource {
    final List<WindowRoot> roots = new ArrayList<WindowRoot>();
    Component main;

    @Override public List<WindowRoot> roots() { return roots; }
    @Override public Component main() { return main; }
}
