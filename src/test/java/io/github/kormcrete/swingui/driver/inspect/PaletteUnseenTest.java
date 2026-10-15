package io.github.kormcrete.swingui.driver.inspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.Roles;
import io.github.kormcrete.swingui.driver.model.Snapshot;
import io.github.kormcrete.swingui.driver.model.Visibility;
import io.github.kormcrete.swingui.driver.widgets.Dialogs;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The palette is the person's, not the app's: nothing the agent reads, finds or records ever includes it. */
class PaletteUnseenTest {

    private final Scene scene = new Scene();

    private ExportPanel exportPanel() {
        ExportPanel panel = new ExportPanel("RecordedUiIT", "com.example", Paths.get("uitest"), null, ExportPanel.JAVA, (c, p) -> { }, (n, f) -> { }, () -> { });
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
            StepsPanel steps = new StepsPanel("▶ Replay all", () -> { }, i -> { }, i -> { }, i -> { }, () -> { }, () -> { });
            steps.setBounds(10, 290, 300, 100);
            steps.validate();
            scene.content.add(steps);
            RecordedTestsPanel testsDir = new RecordedTestsPanel(Paths.get("uitest"), java.util.Collections.singletonList(
                    new Inspector.RecordedTestEntry("place-order", "Places an order", 4, null, Paths.get("uitest/place-order.json"))),
                    2, f -> { }, f -> { }, () -> { });
            testsDir.setBounds(10, 400, 300, 150);
            testsDir.validate();
            scene.content.add(testsDir);
            Bar bar = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
            bar.setBounds(320, 10, 200, 30);
            scene.content.add(bar);
            try {
                String json = new ObjectMapper().writeValueAsString(new Snapshot(Visibility.TREE, scene.names,
                        scene.refs, scene).build(null, null, 400, 20));
                assertThat(json).contains("Save")
                        .doesNotContain("RecordedUiIT").doesNotContain("Export as a JUnit test")
                        .doesNotContain("Replay speed").doesNotContain("Record")
                        .doesNotContain("Load a recorded test").doesNotContain("place-order");
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
    void theFolderDialogIsThePalettesOwn() throws Exception {
        onEdt(() -> {
            JFrame frame = new JFrame();
            FolderDialog dialog = new FolderDialog(frame);
            try {
                assertThat(Roles.agentOwn(dialog)).isTrue();
                assertThat(Overlay.ours(dialog)).isTrue();
                assertThat(dialog.isModal()).isFalse();
                assertThat(dialog.getOwner()).isSameAs(frame);
                assertThat(dialog.isShowing()).isFalse();
            } finally {
                dialog.dispose();
                frame.dispose();
            }
        });
    }

    @Test
    void anAgentFileChooserStepNeverFindsTheFolderDialog() throws Exception {
        onEdt(() -> {
            JFrame frame = new JFrame();
            FolderDialog folders = new FolderDialog(frame);
            JDialog app = new JDialog(frame, "Open");
            app.getContentPane().add(new JFileChooser());
            try {
                assertThat(Dialogs.appChooserWindow(folders)).isFalse();
                assertThat(Dialogs.appChooserWindow(app)).isTrue();
            } finally {
                app.dispose();
                folders.dispose();
                frame.dispose();
            }
        });
    }

    @Test
    void theStepMenuAndASentenceTypedInTheStepsListAreNeverSeen() throws Exception {
        onEdt(() -> {
            JButton save = new JButton("Save the order");
            save.setBounds(320, 50, 120, 24);
            scene.content.add(save);
            StepsPanel steps = new StepsPanel("▶ Replay all", () -> { }, i -> { }, i -> { }, i -> { }, () -> { }, () -> { });
            steps.setBounds(10, 130, 300, 200);
            steps.validate();
            scene.content.add(steps);
            steps.show(java.util.Collections.singletonList(new Inspector.StepLine(1, "person", "click(#save)", false)),
                    new java.util.HashMap<Integer, String>(), 0, null, null);
            steps.describeSelection();
            // a text field like the app's, with the app button's name in it
            JTextField field = steps.nameField();
            field.setText("Save the order");
            StepMenu menu = new StepMenu().item("Describe this step…", true, () -> { });
            try {
                String json = new ObjectMapper().writeValueAsString(new Snapshot(Visibility.TREE, scene.names,
                        scene.refs, scene).build(null, null, 400, 20));
                // the button's name once: the field's text is not in it, nor the steps list's line
                assertThat(json.split("Save the order", -1)).hasSize(2);
                assertThat(json).doesNotContain("click(#save)");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            // the app's button is found and recorded as if the field were not there
            List<Resolved> found = scene.resolver.matches(Locator.of("button", "Save the order"));
            assertThat(found).hasSize(1);
            assertThat(found.get(0).component).isSameAs(save);
            assertThat(scene.inspector.component(save).locator.nth).isNull();
            assertThat(Roles.agentOwn(menu)).isTrue();
            assertThat(Overlay.ours(menu.getComponent(0))).isTrue();
            assertThat(Overlay.ours(field)).isTrue();
        });
    }

    @Test
    void aPopupOfThePaletteIsNoAppPopup() {
        assertThat(Roles.agentOwn(new JPopupMenu() { })).isTrue();
        assertThat(Roles.agentOwn(new JPopupMenu())).isFalse();
    }
}
