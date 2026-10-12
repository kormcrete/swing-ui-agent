package io.github.kormcrete.swingui.testing;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A nested test class that only its owning test launches (on purpose: a failing setup, a stubborn modal). It runs
 * only when the launch sets {@code swingui.fixtures=run}; an IntelliJ package or module run reports it skipped
 * instead of failing it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(FixtureClassCondition.class)
public @interface FixtureClass {
}
