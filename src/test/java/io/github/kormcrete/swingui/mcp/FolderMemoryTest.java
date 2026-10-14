package io.github.kormcrete.swingui.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/** The folder the palette offers next: keyed per working directory, never touching the real user preferences here. */
class FolderMemoryTest {

    @Test
    void theKeyFitsPreferencesForAnyPath() {
        StringBuilder deep = new StringBuilder();
        while (deep.length() < 300) deep.append("/orders-archive");
        String key = FolderMemory.key(Paths.get(deep.toString()));
        assertThat(key).startsWith("flows-");
        assertThat(key.length()).isLessThanOrEqualTo(Preferences.MAX_KEY_LENGTH);
    }

    @Test
    void theSameFolderGivesTheSameKey() {
        Path app = Paths.get("").toAbsolutePath().resolve("order-desk");
        assertThat(FolderMemory.key(app.resolve("../order-desk"))).isEqualTo(FolderMemory.key(app));
        assertThat(FolderMemory.key(Paths.get("order-desk"))).isEqualTo(FolderMemory.key(app));
        assertThat(FolderMemory.key(app.resolveSibling("checkout"))).isNotEqualTo(FolderMemory.key(app));
    }

    @Test
    void inMemoryRemembers() {
        FolderMemory memory = FolderMemory.inMemory();
        assertThat(memory.get()).isNull();
        Path flows = Paths.get("").toAbsolutePath().resolve("uitest");
        memory.put(flows);
        assertThat(memory.get()).isEqualTo(flows);
    }
}
