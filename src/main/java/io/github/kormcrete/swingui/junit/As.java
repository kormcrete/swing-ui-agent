package io.github.kormcrete.swingui.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Who one {@link Client} parameter runs as: an alias from .swing-ui-agent/ui-users.properties. A test gets one
 * client per Client parameter of its test method, bound by position to the pool's lowest free ports. On a test
 * method's parameter it sets that client's login; without it the client logs in as {@link LoginAs}. On a
 * {@code @BeforeEach}/{@code @AfterEach} parameter it picks the test's client with that alias; without it a
 * lifecycle method gets the client at the same position among its own Client parameters.
 */
@java.lang.annotation.Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface As {
    String value();
}
