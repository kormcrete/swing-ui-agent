package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.ContextMenuAction;
import io.github.kormcrete.swingui.flow.verb.AnswerVerb;
import io.github.kormcrete.swingui.flow.verb.CheckVerb;
import io.github.kormcrete.swingui.flow.verb.ChooseFileVerb;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.Doc;
import io.github.kormcrete.swingui.flow.verb.DragVerb;
import io.github.kormcrete.swingui.flow.verb.DropVerb;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;
import io.github.kormcrete.swingui.flow.verb.MenuVerb;
import io.github.kormcrete.swingui.flow.verb.OneOf;
import io.github.kormcrete.swingui.flow.verb.PickVerb;
import io.github.kormcrete.swingui.flow.verb.PressVerb;
import io.github.kormcrete.swingui.flow.verb.Required;
import io.github.kormcrete.swingui.flow.verb.ScrollVerb;
import io.github.kormcrete.swingui.flow.verb.SelectVerb;
import io.github.kormcrete.swingui.flow.verb.SetClipboardVerb;
import io.github.kormcrete.swingui.flow.verb.TypeVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool schemas generated from the verb classes. Only {@link Doc}-annotated fields become
 * arguments, which keeps the recorder fields (via, fragile) out.
 */
final class VerbSchemas {

    static final Map<String, Class<? extends VerbAction>> TOOLS;

    static {
        Map<String, Class<? extends VerbAction>> tools = new LinkedHashMap<String, Class<? extends VerbAction>>();
        tools.put("click", ClickVerb.class);
        tools.put("type", TypeVerb.class);
        tools.put("press", PressVerb.class);
        tools.put("check", CheckVerb.class);
        tools.put("select", SelectVerb.class);
        tools.put("pick", PickVerb.class);
        tools.put("menu", MenuVerb.class);
        tools.put("context_menu", ContextMenuAction.class);
        tools.put("answer", AnswerVerb.class);
        tools.put("choose_file", ChooseFileVerb.class);
        tools.put("drag", DragVerb.class);
        tools.put("drop", DropVerb.class);
        tools.put("expect", ExpectVerb.class);
        tools.put("scroll", ScrollVerb.class);
        tools.put("set_clipboard", SetClipboardVerb.class);
        TOOLS = Collections.unmodifiableMap(tools);
    }

    private VerbSchemas() {}

    static String actionName(Class<?> type) {
        for (JsonSubTypes.Type t : Action.class.getAnnotation(JsonSubTypes.class).value()) {
            if (t.value() == type) return t.name();
        }
        throw new IllegalStateException(type.getName() + " is not a registered step type");
    }

    static String description(Class<?> type) {
        Doc doc = type.getAnnotation(Doc.class);
        return (doc == null ? "" : doc.value() + " ")
                + "Returns {passed, reason?, message, ms, recorded, pending?, realInput?, activations?, snapshot?}; "
                + "the recorded step is what export_test writes (a ref becomes a locator; via:api marks a direct "
                + "Swing call, fragile:true a target that needed nth).";
    }

    static ObjectNode inputSchema(ObjectMapper m, Class<?> type) {
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = m.createArrayNode();
        for (Field f : fields(type)) {
            Doc doc = f.getAnnotation(Doc.class);
            if (doc == null) continue;
            ObjectNode p = schemaOf(m, f.getGenericType());
            p.put("description", doc.value());
            OneOf oneOf = f.getAnnotation(OneOf.class);
            if (oneOf != null) {
                ArrayNode values = p.putArray("enum");
                for (String v : oneOf.value()) values.add(v);
            }
            properties.set(f.getName(), p);
            if (f.isAnnotationPresent(Required.class)) required.add(f.getName());
        }
        properties.putObject("snapshot").put("type", "boolean")
                .put("description", "Also return a snapshot taken after the step.");
        if (required.size() > 0) schema.set("required", required);
        return schema;
    }

    /** Public instance fields: the class's own first, then its superclasses'. */
    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isPublic(mod) && !Modifier.isStatic(mod)) out.add(f);
            }
        }
        return out;
    }

    private static ObjectNode schemaOf(ObjectMapper m, Type type) {
        Class<?> raw = rawClass(type);
        if (raw == Locator.class) return LocatorSchema.of(m);
        ObjectNode s = m.createObjectNode();
        if (raw == String.class) {
            s.put("type", "string");
        } else if (raw == Integer.class || raw == int.class || raw == Long.class || raw == long.class) {
            s.put("type", "integer");
        } else if (raw == Double.class || raw == double.class) {
            s.put("type", "number");
        } else if (raw == Boolean.class || raw == boolean.class) {
            s.put("type", "boolean");
        } else if (List.class.isAssignableFrom(raw)) {
            s.put("type", "array");
            s.set("items", schemaOf(m, typeArgument(type, 0)));
        } else if (Map.class.isAssignableFrom(raw) || raw == Object.class) {
            s.put("type", "object");
        } else {
            s.put("type", "object");
            ObjectNode properties = s.putObject("properties");
            for (Field f : fields(raw)) properties.set(f.getName(), schemaOf(m, f.getGenericType()));
        }
        return s;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class) return (Class<?>) type;
        if (type instanceof ParameterizedType) return (Class<?>) ((ParameterizedType) type).getRawType();
        return Object.class;
    }

    private static Type typeArgument(Type type, int index) {
        return type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments()[index] : Object.class;
    }
}
