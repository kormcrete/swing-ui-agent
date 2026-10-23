package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.driver.CheckpointShot;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.outcome.UiReading;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.Outcome;
import io.github.kormcrete.swingui.testing.RecordingApp;
import io.github.kormcrete.swingui.junit.UiActionFailed;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.testing.RecordingHost;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
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
    void anOutcomeAndARememberedTextAreTheSameOverTheAgent() {
        for (RecordingVerbs v : Arrays.asList(local, served)) {
            final RecordingVerbs verbs = v;
            verbs.readings = () -> UiReading.builder().field("Number",
                    verbs.calls.contains("click button 'Copy' null null null null") ? "ORD-1641" : "ORD-1640").build();
            verbs.rememberAnswer = "Ada Lovelace";
        }
        String[] seen = new String[4];
        int[] at = {0};
        both(ui -> {
            Outcome copy = ui.outcomeOf(() -> ui.button("Copy").click());
            seen[at[0]++] = copy.text("$.fields.Number");
            seen[at[0]++] = ui.table("Orders").cell("Customer").selectedText() + "/" + ui.label("Number").shownText();
            // after it, an action reads nothing
            ui.button("Save").click();
        });
        assertThat(seen).containsExactly("ORD-1641", "Ada Lovelace/Ada Lovelace", "ORD-1641", "Ada Lovelace/Ada Lovelace");
        assertThat(served.calls).isEqualTo(local.calls);
        assertThat(served.calls).contains("readUi", "remember table 'Orders' column=Customer true null");
    }

    @Test
    void aRowTheAppAddsLateResolvesOverTheAgentToo() {
        for (RecordingVerbs v : Arrays.asList(local, served)) {
            final RecordingVerbs verbs = v;
            // the field shows the copy's number only from the fourth reading on: after the click, then once more
            verbs.readings = () -> UiReading.builder().field("Number",
                    verbs.calls.stream().filter("readUi"::equals).count() >= 4 ? "ORD-1641" : "ORD-1640").build();
        }
        String[] seen = new String[2];
        int[] at = {0};
        both(ui -> seen[at[0]++] = ui.outcomeOf(() -> ui.button("Copy").click()).text("$.fields.Number"));

        assertThat(seen).containsExactly("ORD-1641", "ORD-1641");
        assertThat(served.calls).isEqualTo(local.calls);
    }

    @Test
    void theAgentKeepsTheLastTwentyReadingsForReadingAgain() {
        served.readings = () -> UiReading.builder().field("Number", "ORD-1640").build();
        AgentClient ac = new AgentClient("127.0.0.1", agent.port());
        int first = -1;
        for (int i = 0; i < 21; i++) {
            com.fasterxml.jackson.databind.node.ObjectNode a = ac.args();
            a.putObject("action").put("action", "click").putObject("target").put("role", "button").put("name", "Copy");
            a.put("record", false).put("outcome", true);
            int handle = ac.callJson("do_action", a).path("outcomeHandle").asInt();
            if (first < 0) first = handle;
        }
        com.fasterxml.jackson.databind.node.ObjectNode again = ac.args().put("form", "outcome").put("handle", first + 20);
        assertThat(ac.callJson("read", again).path("found").asBoolean()).isTrue();
        final int dropped = first;
        Throwable gone = catchThrowable(() -> ac.callJson("read", ac.args().put("form", "outcome").put("handle", dropped)));
        assertThat(gone).isInstanceOf(DriverException.class).hasMessageContaining("no outcome handle " + dropped)
                .hasMessageContaining("last 20");
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
                Arrays.toString(ui.button("OK").screenshot()), Arrays.toString(ui.screenshot(2, true))
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

    @Test
    void aCheckpointArrivesAsTheSameCall() {
        CheckpointShot answer = new CheckpointShot(new byte[] {9, 8, 7}, 412, 180, Arrays.asList(
                new CheckpointShot.Mask(12, 40, 90, 18, false), new CheckpointShot.Mask(0, 160, 30, 20, true)));
        local.checkpointAnswer = answer;
        served.checkpointAnswer = answer;
        RemoteVerbs wire = new RemoteVerbs(new AgentClient("127.0.0.1", agent.port()));
        Locator lines = inProcess.tab("Orders").table("Lines").row("Number", "ORD-1640").locator();
        List<Locator> masks = Arrays.asList(inProcess.label("Date").locator(),
                inProcess.table("Lines").row("Number", "ORD-1640").cell("Qty").locator());
        Locator main = inProcess.main().locator();

        CheckpointShot a = local.checkpoint(lines, masks);
        CheckpointShot b = wire.checkpoint(lines, masks);
        local.checkpoint(main, Collections.<Locator>emptyList());
        CheckpointShot whole = wire.checkpoint(main, Collections.<Locator>emptyList());

        assertThat(served.calls).isEqualTo(local.calls);
        assertThat(served.calls).containsExactly(
                "checkpoint table 'Lines' [tab:Orders] row={Number=ORD-1640} masks=[label 'Date', "
                        + "table 'Lines' row={Number=ORD-1640} column=Qty]",
                "checkpoint in main masks=[]");
        assertThat(b.png).isEqualTo(a.png);
        assertThat(b.width).isEqualTo(412);
        assertThat(b.height).isEqualTo(180);
        assertThat(b.masks).hasSize(2);
        for (int i = 0; i < 2; i++) {
            CheckpointShot.Mask want = a.masks.get(i);
            CheckpointShot.Mask got = b.masks.get(i);
            assertThat(new int[] {got.x, got.y, got.w, got.h}).containsExactly(want.x, want.y, want.w, want.h);
            assertThat(got.clipped).isEqualTo(want.clipped);
        }
        assertThat(whole.width).isEqualTo(412);
    }

    @Test
    void anAgentWithoutCheckpointsIsACapabilityGap() throws Exception {
        final List<JsonNode> asked = Collections.synchronizedList(new ArrayList<JsonNode>());
        final ObjectMapper json = new ObjectMapper();
        HttpServer older = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        older.createContext("/mcp", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            asked.add(request.path("params").path("arguments"));
            // what an agent from before checkpoints answers: a plain screenshot, the unknown arguments ignored
            ObjectNode r = json.createObjectNode().put("jsonrpc", "2.0");
            r.set("id", request.get("id"));
            ArrayNode content = r.putObject("result").putArray("content");
            content.addObject().put("type", "image").put("mimeType", "image/png")
                    .put("data", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
            content.addObject().put("type", "text").put("text",
                    "{\"width\":640,\"height\":480,\"sourceWidth\":640,\"sourceHeight\":480,\"scaled\":false,\"bytes\":3}");
            byte[] body = r.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        older.start();
        try {
            RemoteVerbs wire = new RemoteVerbs(new AgentClient("127.0.0.1", older.getAddress().getPort()));
            Locator lines = inProcess.table("Lines").locator();

            Throwable t = catchThrowable(() -> wire.checkpoint(lines,
                    Collections.singletonList(inProcess.label("Date").locator())));

            assertThat(t).isInstanceOf(DriverException.class).hasMessage("CAPABILITY_GAP: the agent is older than this client: it "
                    + "takes no checkpoints; use the agent jar of this library's version");
            assertThat(((DriverException) t).reason()).isEqualTo(Reason.CAPABILITY_GAP);
            // only what a checkpoint takes goes out: never palette, scale, maxSize or region
            JsonNode sent = asked.get(0);
            List<String> fields = new ArrayList<String>();
            sent.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("checkpoint", "target", "masks");
            assertThat(sent.path("checkpoint").asBoolean()).isTrue();
            assertThat(sent.path("target").path("name").asText()).isEqualTo("Lines");
            assertThat(sent.path("masks").get(0).path("name").asText()).isEqualTo("Date");
        } finally {
            older.stop(0);
        }
    }
}
