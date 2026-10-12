package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;

import java.util.ArrayList;
import java.util.List;

/** Single configured Jackson mapper for reading/writing {@link Flow} JSON files. */
public final class FlowJson {

    private static final ObjectMapper MAPPER = registerDriverTypes(new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT));

    /** Driver value types are plain objects; the JSON layer leaves their null fields out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class NonNullMixin {}

    /** Registers the driver types' JSON shape on {@code mapper}; call before its first use. */
    public static ObjectMapper registerDriverTypes(ObjectMapper mapper) {
        mapper.addMixIn(io.github.kormcrete.swingui.driver.model.Locator.class, NonNullMixin.class);
        mapper.addMixIn(io.github.kormcrete.swingui.driver.model.Node.class, NonNullMixin.class);
        mapper.addMixIn(io.github.kormcrete.swingui.driver.PendingDialog.class, NonNullMixin.class);
        mapper.addMixIn(io.github.kormcrete.swingui.driver.Expectation.class, NonNullMixin.class);
        mapper.addMixIn(io.github.kormcrete.swingui.driver.Expectation.Count.class, NonNullMixin.class);
        mapper.addMixIn(io.github.kormcrete.swingui.driver.Expectation.Cell.class, NonNullMixin.class);
        return mapper;
    }

    private FlowJson() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** The step types a flow step may name in its {@code action} field: {@link Action}'s subtypes, in order. */
    public static List<String> stepTypes() {
        List<String> names = new ArrayList<String>();
        for (JsonSubTypes.Type t : Action.class.getAnnotation(JsonSubTypes.class).value()) names.add(t.name());
        return names;
    }

    /**
     * A step naming no type, or one there is not, as BAD_INPUT naming it and the step types there are, instead of
     * Jackson's own message.
     */
    public static DriverException unknownStepType(InvalidTypeIdException e) {
        String named = e.getTypeId() == null
                ? "a step needs an 'action' naming its type"
                : "there is no step type '" + e.getTypeId() + "'";
        return new DriverException(Reason.BAD_INPUT, named + "; the step types are " + stepTypes());
    }

    /** Parses a flow. Fails as BAD_INPUT: an unknown step type as {@link #unknownStepType}, else invalid JSON. */
    public static Flow parse(String json) {
        try {
            return MAPPER.readValue(json, Flow.class);
        } catch (InvalidTypeIdException e) {
            throw unknownStepType(e);
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            String where = at == null ? "" : " (line " + at.getLineNr() + ", column " + at.getColumnNr() + ")";
            DriverException bad = new DriverException(Reason.BAD_INPUT,
                    "not a valid flow: " + e.getOriginalMessage() + where);
            bad.initCause(e);
            throw bad;
        }
    }

    public static String write(Flow flow) {
        try {
            return MAPPER.writeValueAsString(flow);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write flow JSON: " + e.getMessage(), e);
        }
    }

    /** Reads and parses a flow file. Fails as {@link #parse} does, or as BAD_INPUT when unreadable, naming the file. */
    public static Flow load(java.nio.file.Path path) {
        String json;
        try {
            json = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            DriverException bad = new DriverException(Reason.BAD_INPUT, "cannot read flow file " + path + ": " + e);
            bad.initCause(e);
            throw bad;
        }
        try {
            return parse(json);
        } catch (DriverException e) {
            DriverException named = new DriverException(e.reason(),
                    "flow file " + path + ": " + DriverException.stripReason(e.getMessage(), e.reason()), e.details());
            named.initCause(e);
            throw named;
        }
    }
}
