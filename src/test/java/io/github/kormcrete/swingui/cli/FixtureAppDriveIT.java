package io.github.kormcrete.swingui.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.fixture.FixtureApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole product on this OS and Java: FixtureApp starts in its own JVM with the packaged agent jar
 * ({@code -javaagent}, so on Java 9+ the agent opens java.desktop itself), and the CLI drives it the way a coding
 * agent does - typing, clicking, keys that follow focus the app moved itself, Tab, a modal dialog, a drop and a
 * context menu. Run by failsafe after {@code package}; on every OS CI covers, this is what makes the platform green.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FixtureAppDriveIT {

    private static final Pattern LISTENING = Pattern.compile("swing-ui-agent listening on http://127\\.0\\.0\\.1:(\\d+)/mcp");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Process app;
    private static int port;
    private static final StringBuffer appOutput = new StringBuffer();

    @BeforeAll
    static void startTheAppWithTheAgent() throws Exception {
        Path agentJar = Paths.get(System.getProperty("swingui.agentJar", "missing: set swingui.agentJar"));
        assertThat(Files.isRegularFile(agentJar)).as("agent jar %s (run through mvn verify)", agentJar).isTrue();
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        // only the fixture's own classes: the agent must come from the packaged jar, not from target/classes
        String fixtureClasses = Paths.get(FixtureApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        app = new ProcessBuilder(java, "-javaagent:" + agentJar + "=port=0", "-cp", fixtureClasses,
                FixtureApp.class.getName()).redirectErrorStream(true).start();
        CountDownLatch listening = new CountDownLatch(1);
        Thread drain = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(app.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    appOutput.append(line).append('\n');
                    Matcher m = LISTENING.matcher(line);
                    if (m.find()) {
                        port = Integer.parseInt(m.group(1));
                        listening.countDown();
                    }
                }
            } catch (Exception ignored) {
                // the app stopped
            }
        }, "fixture-app-output");
        drain.setDaemon(true);
        drain.start();
        assertThat(listening.await(60, TimeUnit.SECONDS)).as("the agent never listened:\n" + appOutput).isTrue();
    }

    @AfterAll
    static void stopTheApp() throws Exception {
        if (app == null) return;
        app.destroy();
        if (!app.waitFor(10, TimeUnit.SECONDS)) app.destroyForcibly();
    }

    @Test
    @Order(1)
    void theDriverStartsWithEveryRequiredCapability() {
        assertThat(appOutput.toString()).contains("UI driver installed").doesNotContain("MISSING");
    }

    @Test
    @Order(2)
    void typeAndClick() throws Exception {
        step("type", "{'target':{'name':'customer'},'text':'ACME'}");
        step("click", "{'target':{'name':'save'}}");
        step("expect", "{'target':{'name':'status'},'text':'saved ACME'}");
    }

    @Test
    @Order(3)
    void keysFollowTheFocusTheAppMovedItselfAndTab() throws Exception {
        step("click", "{'target':{'name':'new'}}");
        step("press", "{'keys':'5'}");
        step("expect", "{'target':{'name':'customer'},'value':'5'}");
        step("press", "{'keys':'Tab'}");
        step("press", "{'keys':'7'}");
        step("expect", "{'target':{'name':'notes'},'value':'7'}");
    }

    @Test
    @Order(4)
    void aModalDialogAndTypingAfterIt() throws Exception {
        step("click", "{'target':{'name':'delete'}}");
        step("answer", "{'button':'Yes'}");
        step("expect", "{'target':{'name':'status'},'text':'deleted'}");
        step("type", "{'target':{'name':'notes'},'text':'after the dialog'}");
        step("expect", "{'target':{'name':'notes'},'value':'after the dialog'}");
    }

    @Test
    @Order(5)
    void aDropAndAContextMenu() throws Exception {
        step("drop", "{'target':{'name':'attachments'},'text':'ORD-1640.pdf'}");
        step("expect", "{'target':{'name':'status'},'text':'attached ORD-1640.pdf'}");
        step("context_menu", "{'target':{'name':'attachments'},'path':['Clear attachments']}");
        step("expect", "{'target':{'name':'status'},'text':'attachments cleared'}");
    }

    @Test
    @Order(6)
    void typeIntoASpinnerCellAndAValueOutOfRangeKeepsTheLastValidOne() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Qty'},'text':'3','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Qty','expected':'3'}}");
        step("expect", "{'target':{'name':'status'},'text':'qty 3 code 10'}");
        // the status moves on, so "qty 3" again can only come from the edit of the next step ending
        step("click", "{'target':{'name':'save'}}");
        step("expect", "{'target':{'name':'status'},'text':'saved 5'}");
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Qty'},'text':'0','commit':true}");
        step("expect", "{'target':{'name':'status'},'text':'qty 3 code 10'}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Qty','expected':'3'}}");
    }

    @Test
    @Order(7)
    void aRefusedEditIsCancelledAndTheCellKeepsItsValue() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'abc','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'10'}}");
        step("expect", "{'target':{'name':'status'},'text':'code cancelled'}");
        // the editor is gone: a second type starts a fresh edit and this one is accepted
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'42','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'42'}}");
    }

    @Test
    @Order(8)
    void aRefusalTheAppExplainsInADialogIsLeftToTheTest() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Ref'},'text':'x1','commit':true}");
        // one refusal: the driver asked the editor no second time under the app's dialog
        step("expect", "{'target':{'name':'status'},'text':'ref refused 1'}");
        step("answer", "{'button':'OK'}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Ref','expected':'20'}}");
        // typing into another cell: the click asks Ref's editor to stop, and its second refusal is left to the test
        refused("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'5','commit':true}",
                "blocked by dialog Ref");
        step("expect", "{'target':{'name':'status'},'text':'ref refused 2'}");
        step("answer", "{'button':'OK'}");
        // the edit is still open: typing again goes into its field, without asking the editor to stop first
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Ref'},'text':'7','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Ref','expected':'7'}}");
        step("expect", "{'target':{'name':'status'},'text':'qty 3 code 42'}");
    }

    @Test
    @Order(9)
    void anEditorThatKeepsItsEditOpenNeverTakesTheTextOfAnotherCell() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'11','commit':true}");
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'abc'}");
        // Code's editor refuses to stop, silently: Qty's edit cannot start, and Code's field must not take the 5
        refused("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Qty'},'text':'5','commit':true}",
                "column 'Code' is still being edited");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'11'}}");
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'12','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'12'}}");
    }

    /** Runs one CLI call, {@code args} in single quotes for readability, and requires it to pass. */
    private static void step(String tool, String args) throws Exception {
        JsonNode result = call(tool, args);
        assertThat(result.path("passed").asBoolean()).as(result.path("why").asText()).isTrue();
    }

    /** Runs one CLI call and requires it to be refused as NOT_ACTIONABLE with a message holding {@code because}. */
    private static void refused(String tool, String args, String because) throws Exception {
        JsonNode result = call(tool, args);
        String why = result.path("why").asText();
        assertThat(result.path("passed").asBoolean()).as(why).isFalse();
        assertThat(result.path("reason").asText()).as(why).isEqualTo("NOT_ACTIONABLE");
        assertThat(result.path("message").asText()).as(why).contains(because);
    }

    /** The CLI's answer, which must exit 0, with a "why" for assertion messages. */
    private static JsonNode call(String tool, String args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = UiCli.run(new String[] {"--port", String.valueOf(port), tool, args.replace('\'', '"')},
                new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"));
        String answer = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String why = tool + " " + args + " -> exit " + exit + ": " + answer + new String(err.toByteArray(),
                StandardCharsets.UTF_8) + "\napp output:\n" + appOutput;
        assertThat(exit).as(why).isZero();
        ObjectNode result = (ObjectNode) JSON.readTree(answer);
        return result.put("why", why);
    }
}
