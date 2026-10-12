package io.github.kormcrete.swingui.junit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A UI test against a running app with swing-ui-agent attached. Each test leases one client per {@link Client}
 * parameter of its test method, resets it and binds it. A reset closes modal dialogs; with an app plugin it also
 * runs the plugin's reset (tabs, login as {@link LoginAs} or {@link As}). Name the class {@code *UiIT}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(UiTestExtension.class)
@Tag("ui")
public @interface UiTest {
}
