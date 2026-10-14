package io.github.kormcrete.swingui.driver.inspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.Roles;
import io.github.kormcrete.swingui.driver.model.Snapshot;
import io.github.kormcrete.swingui.driver.model.Visibility;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The palette is the person's, not the app's: nothing the agent reads, finds or records ever includes it. */
class PaletteUnseenTest {

    private final Scene scene = new Scene();

    private ExportPanel exportPanel() {
        ExportPanel panel = new ExportPanel("RecordedUiIT", "com.example", (c, p) -> { }, () -> { });
        panel.setBounds(10, 130, 300, 150);
        panel.validate();
        scene.content.add(panel);
        return panel;
    }

    private void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    @Test
    void theSnapshotHasNoNodeOfThePalette() throws Exception {
        onEdt(() -> {
            exportPanel();
            StepsPanel steps = new StepsPanel(() -> { }, i -> { }, i -> { }, i -> { }, () -> { });
            steps.setBounds(10, 290, 300, 100);
            steps.validate();
            scene.content.add(steps);
            Bar bar = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
            bar.setBounds(320, 10, 200, 30);
            scene.content.add(bar);
            try {
                String json = new ObjectMapper().writeValueAsString(new Snapshot(Visibility.TREE, scene.names,
                        scene.refs, scene).build(null, null, 400, 20));
                assertThat(json).contains("Save")
                        .doesNotContain("RecordedUiIT").doesNotContain("Export as a JUnit test")
                        .doesNotContain("Replay speed").doesNotContain("Record");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void theResolverFindsOnlyTheAppsExportButton() throws Exception {
        onEdt(() -> {
            JButton export = new JButton("Export");
            export.setBounds(320, 50, 80, 24);
            scene.content.add(export);
            exportPanel();
            List<Resolved> found = scene.resolver.matches(Locator.of("button", "Export"));
            assertThat(found).hasSize(1);
            assertThat(found.get(0).component).isSameAs(export);
        });
    }

    @Test
    void theRecorderNeedsNoNthOrScopeForIt() throws Exception {
        onEdt(() -> {
            JButton export = new JButton("Export");
            export.setBounds(320, 50, 80, 24);
            scene.content.add(export);
            exportPanel();
            Locator locator = scene.inspector.component(export).locator;
            assertThat(locator.nth).isNull();
            assertThat(locator.scope).isNull();
        });
    }

    @Test
    void aPaletteFieldNeverTakesAnAppLabel() throws Exception {
        onEdt(() -> {
            JLabel notes = new JLabel("Notes:");
            notes.setBounds(10, 200, 60, 20);
            JTextField appField = new JTextField();
            appField.setBounds(300, 200, 120, 20);
            JTextField paletteField = new JTextField() { };
            paletteField.setBounds(80, 200, 100, 20);
            scene.content.add(notes);
            scene.content.add(paletteField);
            scene.content.add(appField);
            List<Resolved> found = scene.resolver.matches(Locator.of("text", "Notes"));
            assertThat(found).hasSize(1);
            assertThat(found.get(0).component).isSameAs((Component) appField);
        });
    }

    @Test
    void aPopupOfThePaletteIsNoAppPopup() {
        assertThat(Roles.agentOwn(new JPopupMenu() { })).isTrue();
        assertThat(Roles.agentOwn(new JPopupMenu())).isFalse();
    }
}
