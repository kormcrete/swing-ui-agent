package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.FixtureClass;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class ModuleTargetTest {

    @FixtureClass
    @Tag("ui")
    static class OneUiTest {
        @Test void passes() { }
    }

    @Test
    void aClassFromATargetDirectoryResolvesToThatModulesTargetWhateverTheWorkingDirectory() {
        Path target = ModuleTarget.of(ModuleTargetTest.class);
        assertThat(target.isAbsolute()).isTrue();
        assertThat(target.getFileName().toString()).isEqualTo("target");
        assertThat(target.getParent().resolve("pom.xml")).as("the module directory, whatever the checkout is named")
                .isRegularFile();
    }

    @Test
    void aClassKnownByNameResolvesLikeTheClass() {
        assertThat(ModuleTarget.of(OneUiTest.class.getName())).isEqualTo(ModuleTarget.of(ModuleTargetTest.class));
    }

    @Test
    void noCodeSourceOrAnUnknownClassFallsBackToTheWorkingDirectorysTarget() {
        assertThat(ModuleTarget.of(String.class)).isEqualTo(Paths.get("target"));
        assertThat(ModuleTarget.of("no.such.Clazz")).isEqualTo(Paths.get("target"));
        assertThat(ModuleTarget.of((String) null)).isEqualTo(Paths.get("target"));
    }

    @Test
    void theDefaultRunReportLandsInTheTestModulesTarget() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        LauncherFactory.create().execute(
                LauncherDiscoveryRequestBuilder.request().selectors(selectClass(OneUiTest.class)).configurationParameter("swingui.fixtures", "run").build(),
                new UiRunReport());
        Path file = ModuleTarget.of(ModuleTargetTest.class).resolve("ui-runs").resolve("latest.txt");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(Instant.parse(lines.get(0).split(" ")[1])).isAfter(before);
        assertThat(lines.get(lines.size() - 1)).isEqualTo("DONE passed=1 failed=0 skipped=0 aborted=0");
    }

    @Test
    void evidenceGoesUnderTheTestModulesTarget() {
        assertThat(UiTestExtension.evidenceRoot(ModuleTargetTest.class))
                .isEqualTo(ModuleTarget.of(ModuleTargetTest.class).resolve("ui-failures"));
    }
}
