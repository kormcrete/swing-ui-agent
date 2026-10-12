package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.spi.PluginParts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * plugin_call: invokes one method of a plugin's hooks interface by name, arguments converted from JSON to the
 * parameter types, the result returned as {@code {"value": …}}. A hook's unchecked exception passes through, so a
 * DriverException keeps its reason; a checked one becomes APP_ERROR. One method per name, so a call is never
 * ambiguous.
 */
final class PluginCalls {

    private PluginCalls() {}

    static ObjectNode call(Plugins plugins, String pluginName, String method, JsonNode args, ObjectMapper mapper) {
        PluginSlot slot = plugins.slot(pluginName);
        PluginParts parts = slot.readyParts("plugin_call");
        if (parts.hooksType == null) {
            throw new DriverException(Reason.CAPABILITY_GAP, "plugin '" + slot.name() + "' offers no hooks");
        }
        Method m = method(slot.name(), parts.hooksType, method);
        Type[] types = m.getGenericParameterTypes();
        int given = args == null || args.isNull() ? 0 : args.size();
        if (given != types.length) {
            throw new DriverException(Reason.BAD_INPUT,
                    "hook '" + method + "' takes " + types.length + " arguments, got " + given);
        }
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            values[i] = mapper.convertValue(args.get(i), mapper.getTypeFactory().constructType(types[i]));
        }
        Object result;
        try {
            result = m.invoke(parts.hooks, values);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new DriverException(Reason.APP_ERROR, method + ": " + c);
        } catch (IllegalAccessException e) {
            throw new DriverException(Reason.APP_ERROR, method + ": " + e);
        }
        ObjectNode r = mapper.createObjectNode();
        r.set("value", mapper.valueToTree(result));
        return r;
    }

    static Method method(String plugin, Class<?> type, String name) {
        List<Method> found = new ArrayList<Method>();
        TreeSet<String> names = new TreeSet<String>();
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            names.add(m.getName());
            if (m.getName().equals(name)) found.add(m);
        }
        if (found.isEmpty()) {
            throw new DriverException(Reason.BAD_INPUT,
                    plugin + " has no hook '" + name + "' (known: " + String.join(", ", names) + ")");
        }
        if (found.size() > 1) {
            throw new DriverException(Reason.BAD_INPUT,
                    "hook '" + name + "' is overloaded; plugin_call needs one method per name");
        }
        return found.get(0);
    }
}
