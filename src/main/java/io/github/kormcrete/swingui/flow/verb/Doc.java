package io.github.kormcrete.swingui.flow.verb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Tool and field description for the generated MCP schema. Only documented fields are offered as arguments. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface Doc {
    String value();
}
