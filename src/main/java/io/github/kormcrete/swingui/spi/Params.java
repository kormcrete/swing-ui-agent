package io.github.kormcrete.swingui.spi;

/**
 * The concrete values a {@link DomainStep} runs with, by param name, e.g. {@code product = "A4 paper, 500 sheets"},
 * {@code qty = "3"}. An optional param that was not given holds its default.
 */
public interface Params {
    /** The value of the param {@code name}, as given. */
    String text(String name);

    /** The value of the {@link ParamType#INT} param {@code name} as a number. */
    int integer(String name);
}
