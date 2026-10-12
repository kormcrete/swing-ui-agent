package io.github.kormcrete.swingui.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Who the test runs as: an alias from .swing-ui-agent/ui-users.properties. A method's wins over its class's. Needs an
 * app plugin that logs in.
 */
@java.lang.annotation.Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoginAs {
    String value();
}
