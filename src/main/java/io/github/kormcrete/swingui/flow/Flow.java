package io.github.kormcrete.swingui.flow;

import java.util.ArrayList;
import java.util.List;

/** A named happy-flow: an ordered list of actions to replay. */
public class Flow {
    public String name;
    public String description;
    public List<Action> steps = new ArrayList<>();
}
