package io.github.kormcrete.swingui.flow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/** A named list of steps to replay: a recorded test, or one live step. */
@JsonPropertyOrder({"version", "name", "description", "start", "steps"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Flow {
    /** The file format version ({@link RecordedTestFiles#VERSION}); null in a recorded test that never was a file. */
    public Integer version;
    public String name;
    public String description;
    /** Where the recorded test begins: {@code "reset"} = right after a session reset, as an exported test does. */
    public String start;
    public List<Action> steps = new ArrayList<>();
}
