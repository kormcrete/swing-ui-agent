package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** A plugin's hooks interface, out of process: each call is one plugin_call, the result converted to the return type. */
final class RemoteHooks implements InvocationHandler {

    private final AgentClient agent;
    private final Class<?> type;
    private final String plugin;

    private RemoteHooks(AgentClient agent, Class<?> type, String plugin) {
        this.agent = agent;
        this.type = type;
        this.plugin = plugin;
    }

    static <T> T of(AgentClient agent, Class<T> type, String plugin) {
        if (!type.isInterface()) throw new IllegalArgumentException(type.getName() + " is not an interface");
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                new RemoteHooks(agent, type, plugin)));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "toString": return "remote " + type.getSimpleName();
                case "hashCode": return System.identityHashCode(proxy);
                default: return proxy == args[0];
            }
        }
        ObjectNode a = agent.args();
        if (plugin != null) a.put("plugin", plugin);
        a.put("method", method.getName());
        ArrayNode list = a.putArray("args");
        if (args != null) for (Object v : args) list.add(agent.json().valueToTree(v));
        JsonNode value = agent.callJson("plugin_call", a).get("value");
        Class<?> rt = method.getReturnType();
        if (rt == void.class) return null;
        if (value == null || value.isNull()) {
            if (rt.isPrimitive()) {
                throw new DriverException(Reason.APP_ERROR, method.getName() + " returned null for a " + rt.getName());
            }
            return null;
        }
        return agent.json().convertValue(value, agent.json().getTypeFactory().constructType(method.getGenericReturnType()));
    }
}
