package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.testing.RecordingApp;
import io.github.kormcrete.swingui.junit.UiActionFailed;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.testing.RecordingHost;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.Consumer;

import static io.github.kormcrete.swingui.junit.UiAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RemoteParityTest {

    private RecordingVerbs local;
    private RecordingVerbs served;
    private InJvmAgent agent;
    private Client inProcess;
    private Client remote;

    @BeforeEach
    void start() {
        local = new RecordingVerbs();
        served = new RecordingVerbs();
        for (RecordingVerbs v : Arrays.asList(local, served)) answers(v);
        agent = InJvmAgent.start(served, new PluginParts(new RecordingHost(served), null));
        inProcess = new Client(local, new RecordingApp(local.calls), "run-1");
        AgentClient ac = new AgentClient("127.0.0.1", agent.port());
        remote = new Client(new RemoteVerbs(ac), new RemoteApp(ac), "run-1");
    }

    @AfterEach
    void stop() {
        agent.stop();
    }

    /** The same answers on both sides, so every read has one. */
    private static void answers(RecordingVerbs v) {
        Node label = new Node("label", "Status");
        label.value = "open";
        v.readAnswer = label;
        v.readAllAnswer = Arrays.asList(new Node("label", "A"), new Node("label", "B"));
        Geometry g = new Geometry();
        g.width = 640;
        g.height = 480;
        v.geometryAnswer = g;
        v.menuItemsAnswer = Arrays.asList("New", "Open");
        v.clipboard = "clip";
        v.fileChooserAnswer = new FileChooserState("/tmp", "a.xlsx", true, "Aqua");
    }

    private void both(Consumer<Client> script) {
        script.accept(inProcess);
        script.accept(remote);
    }

    @Test
    void everyVerbArrivesAsTheSameCall() {
        both(ui -> {
            ui.button("OK").click();
            ui.main().table("Orders").row("No", "ORD-1640").cell("Status").doubleClick();
            ui.field("Name").fill("Order " + ui.runId());
            ui.field("Name").type("x", false, true);
            ui.press("ctrl S");
            ui.field("Name").press("ENTER");
            ui.checkbox("Active").check();
            ui.checkbox("Active").uncheck();
            ui.combo("Type").select("Retail");
            ui.menu("File", "New");
            ui.pick("Master data", "Customers");
            ui.table("Orders").rowAt(0).rightClick("Copy");
            ui.table("Orders").rowAt(0).click("right", 1);
            ui.answer("OK");
            ui.chooseFile("/tmp/a.xlsx", true);
            ui.focusFileChooserName();
            ui.table("A").rowAt(0).dragTo(ui.table("B"));
            ui.table("A").drop("/tmp/x.txt");
            ui.table("A").dropText("hello");
            ui.table("A").scrollTo("bottom");
            ui.table("A").scrollBy(2);
            ui.table("A").scrollIntoView();
            ui.setClipboard("hello");
            ui.table("A").column("Name").resizeColumn(-40);
            ui.main().resizeTo(1200, 800);
            ui.dialog("Notice").close();
            ui.button("OK").hover();
            ui.button("OK").unhover();
            ui.main().clickAt(0.5, 0.25);
            assertThat(ui.button("OK")).isEnabled();
            assertThat(ui.table("A")).hasRowCount("gte", 1);
            assertThat(ui.label("Status")).hasText("open");
            ui.navigate("Orders");
            ui.closeActiveTab();
        });
        assertThat(served.calls).isEqualTo(local.calls);
    }

    @Test
    void everyReadAnswersTheSame() {
        Object[] a = reads(inProcess);
        Object[] b = reads(remote);
        assertThat(b).isEqualTo(a);
        assertThat(served.calls).isEqualTo(local.calls);
    }

    private static Object[] reads(Client ui) {
        return new Object[] {
                ui.label("Status").text(), ui.label("Status").value(), ui.label("x").count(), ui.label("x").texts(),
                ui.label("Status").exists(), ui.table("A").bounds().width(), ui.menuItems("File"),
                ui.clipboardText(), ui.fileChooserFolder(), ui.fileChooserName(), ui.modalShowing(),
                Arrays.toString(ui.button("OK").screenshot())
        };
    }

    @Test
    void aFailureReadsTheSame() {
        for (RecordingVerbs v : Arrays.asList(local, served)) {
            v.failNext = new DriverException(Reason.NOT_FOUND, "no button 'Store'");
            v.candidates = Arrays.asList("button 'Save'");
        }
        Throwable a = catchThrowable(() -> inProcess.button("Store").click());
        Throwable b = catchThrowable(() -> remote.button("Store").click());
        assertThat(b).isInstanceOf(UiActionFailed.class).hasMessage(a.getMessage());
        assertThat(((UiActionFailed) b).reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(((UiActionFailed) b).candidates()).containsExactly("button 'Save'");
    }
}
