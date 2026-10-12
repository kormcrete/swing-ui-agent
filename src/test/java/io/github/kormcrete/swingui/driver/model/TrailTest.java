package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A document-host tab strip - recognised by its tabs installing their own tab-header component rather than a plain
 * title - contributes no scope step, because its title is instance data (a record number, a name). An inner, plain
 * tab strip still does. An untitled tab never bakes {@code "tab:null"} into a scope.
 */
class TrailTest {

    /** {@code root} -> document host (one tab, a tab-header component, title {@code orderTitle}) ->
     * order editor content -> inner tabs (General, Attachments selected) -> Attachments content -> target. */
    private static final class DocumentScene {
        final JPanel root = new JPanel(null);
        final JTabbedPane docHost = new JTabbedPane();
        final JPanel orderEditor = new JPanel(null);
        final JTabbedPane innerTabs = new JTabbedPane();
        final JPanel attachments = new JPanel(null);
        final JButton target = new JButton("Target");
        final JButton decoy = new JButton("Target");

        final Refs refs = new Refs(c -> true);
        final FakeWindows windows = new FakeWindows();
        final Names names = new Names(Visibility.TREE);
        final Resolver resolver = new Resolver(Visibility.TREE, names, refs, windows);
        final Recorder recorder = new Recorder(resolver, windows);

        DocumentScene(String orderTitle) {
            docHost.addTab(orderTitle, orderEditor);
            docHost.setTabComponentAt(0, new JLabel(orderTitle));
            orderEditor.setVisible(true); // a laid-out tabbed pane shows its selected tab

            innerTabs.addTab("General", new JPanel());
            innerTabs.addTab("Attachments", attachments);
            innerTabs.setSelectedIndex(1);
            attachments.setVisible(true);
            orderEditor.add(innerTabs);

            attachments.add(target);
            root.add(docHost);
            root.add(decoy);

            windows.main = root;
            windows.roots.add(new WindowRoot(root, "window", "OrderDesk", false, null, true));
        }

        String recordTargetRef() {
            return refs.add(target, null);
        }
    }

    @Test
    void theDocumentHostContributesNoScopeStepButTheInnerTabDoes() {
        DocumentScene scene = new DocumentScene("[Order] ORD-1651");
        assertThat(Trail.of(scene.target)).containsExactly("tab:Attachments");
    }

    @Test
    void theScopeIsIdenticalForADifferentRecordNumber() {
        DocumentScene a = new DocumentScene("[Order] ORD-1651");
        DocumentScene b = new DocumentScene("[Order] ORD-1999");
        assertThat(Trail.of(a.target)).isEqualTo(Trail.of(b.target));
        assertThat(Trail.of(b.target)).containsExactly("tab:Attachments");
    }

    @Test
    void otherDocumentTitlesAreAlsoNeverBaked() {
        DocumentScene customer = new DocumentScene("[Customer] Test Customer (13564)");
        DocumentScene user = new DocumentScene("[User] alice");
        assertThat(Trail.of(customer.target)).containsExactly("tab:Attachments");
        assertThat(Trail.of(user.target)).containsExactly("tab:Attachments");
    }

    @Test
    void anUntitledTabContributesNoScopeStep() {
        JPanel root = new JPanel(null);
        JTabbedPane untitled = new JTabbedPane();
        JPanel content = new JPanel(null);
        JButton target = new JButton("X");
        content.add(target);
        untitled.addTab(null, content);
        root.add(untitled);

        assertThat(Trail.of(target)).isEmpty();
    }

    @Test
    void aTabScopeStepNeedsATitle() {
        DriverException e = catchThrowableOfType(() -> ScopeStep.tab(null), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void anUntitledSelectedTabDoesNotAbortResolution() {
        JPanel root = new JPanel(null);
        JTabbedPane untitled = new JTabbedPane();
        JPanel content = new JPanel(null);
        JButton target = new JButton("X");
        content.add(target);
        content.setVisible(true);
        untitled.addTab(null, content);
        untitled.setSelectedIndex(0);
        root.add(untitled);

        Refs refs = new Refs(c -> true);
        FakeWindows windows = new FakeWindows();
        windows.main = root;
        windows.roots.add(new WindowRoot(root, "window", "OrderDesk", false, null, true));
        Names names = new Names(Visibility.TREE);
        Resolver resolver = new Resolver(Visibility.TREE, names, refs, windows);

        Resolved r = resolver.resolve(Locator.of("button", "X"));
        assertThat(r.component).isSameAs(target);
        assertThat(r.trail).doesNotContain("tab:null");
    }

    @Test
    void recordedLocatorOmitsTheDocumentTabAndReplaysAgainstADifferentRecord() {
        DocumentScene a = new DocumentScene("[Order] ORD-1651");
        Resolved resolvedA = a.resolver.resolve(Locator.ofRef(a.recordTargetRef()));
        Recorder.Recorded recorded = a.recorder.record(resolvedA);

        assertThat(recorded.locator.scope).isEqualTo(Arrays.asList("tab:Attachments"));
        assertThat(recorded.fragile).isFalse();

        // Round trip against the scene it was recorded from.
        assertThat(a.resolver.resolve(recorded.locator).component).isSameAs(a.target);

        // The property that matters: the exact same locator replays against a different record.
        DocumentScene b = new DocumentScene("[Order] ORD-1999");
        assertThat(b.resolver.resolve(recorded.locator).component).isSameAs(b.target);
    }

    @Test
    void aSectionAroundAnInnerTabIsStillRecorded() {
        DocumentScene scene = new DocumentScene("[Order] ORD-1651");
        JPanel section = new JPanel();
        section.setBorder(javax.swing.BorderFactory.createTitledBorder("Notes"));
        JButton note = new JButton("Save");
        section.add(note);
        scene.attachments.add(section);

        assertThat(Trail.of(note)).isEqualTo(Arrays.asList("tab:Attachments", "section:Notes"));
    }
}
