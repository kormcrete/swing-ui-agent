package io.github.kormcrete.swingui.flow;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** The groups of whole steps, as flat indexes into {@link #steps}; the file nests them, this list stays beside the flat one. */
    @JsonIgnore public List<FlowGroup> groups = new ArrayList<>();
    /** The plugins its {@code do} steps need, as a version 3 file names them; empty when none named. */
    @JsonIgnore public List<String> requires = new ArrayList<>();
    /** The line each of {@link #steps} starts at in the file it was read from; empty when it was not read from one. */
    @JsonIgnore public List<Integer> lines = new ArrayList<>();
}
