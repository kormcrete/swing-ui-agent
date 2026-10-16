package io.github.kormcrete.swingui.spi;

/** What a {@link DomainStep} param holds. Values always travel as text; the type says which text is valid. */
public enum ParamType {
    /** Any text, e.g. a product name: {@code "Gel pen, black"}. Read it with {@link Params#text}. */
    TEXT,
    /** A whole number written in digits, e.g. a quantity: {@code "3"}. Read it with {@link Params#integer}. */
    INT
}
