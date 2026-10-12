package io.github.kormcrete.swingui.spi;

/** Who a test runs as: a user alias the app plugin resolves, or explicit credentials. */
public final class ResetSpec {
    public static final String DEFAULT_ALIAS = "default";

    public final String alias;
    public final String user;
    public final String password;

    private ResetSpec(String alias, String user, String password) {
        this.alias = alias;
        this.user = user;
        this.password = password;
    }

    /** The alias, trimmed; a blank or null alias is {@link #DEFAULT_ALIAS}. */
    public static ResetSpec alias(String alias) {
        String a = alias == null || alias.trim().isEmpty() ? DEFAULT_ALIAS : alias.trim();
        return new ResetSpec(a, null, null);
    }

    public static ResetSpec credentials(String user, String password) {
        if (user == null || user.trim().isEmpty()) throw new IllegalArgumentException("credentials need a user");
        return new ResetSpec(null, user.trim(), password == null ? "" : password);
    }

    /** Two specs with the same key log in the same way. */
    public String key() {
        return alias != null ? "alias:" + alias : "user:" + user;
    }

    @Override public String toString() {
        return alias != null ? alias : user;
    }
}
