package io.github.kormcrete.swingui.flow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/** A named happy-flow: an ordered list of actions to replay. */
@JsonPropertyOrder({"version", "name", "description", "start", "steps"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Flow {
    /** The file format version ({@link FlowFiles#VERSION}); null in a flow that never was a file. */
    public Integer version;
    public String name;
    public String description;
    /** Where the flow begins: {@code "reset"} = right after a session reset, as an exported test does. */
    public String start;
    public List<Action> steps = new ArrayList<>();
}
