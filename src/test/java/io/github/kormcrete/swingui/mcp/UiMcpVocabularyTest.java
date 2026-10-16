package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.agent.Plugins;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpVocabularyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UiMcpReadToolsTest.DriverHost backend = new UiMcpReadToolsTest.DriverHost();

    private static DomainStep addToCart() {
        return DomainStep.act("addToCart", "Add {product} to the cart")
                .about("Adds a product to the cart of the open new order")
                .param("product", ParamType.TEXT).choices(() -> Arrays.asList("A4 paper, 500 sheets", "Gel pen, black"))
                .param("qty", ParamType.INT).optional("1")
                .code((ui, p) -> {
                    ui.tab("New order*").table().row("Product", p.text("product")).cell("Product").click();
                    ui.tab("New order*").button("Add to cart").click();
                })
                .build();
    }

    private static DomainStep cartTotalIs() {
        return DomainStep.check("cartTotalIs", "The cart total is {total}")
                .param("total", ParamType.TEXT)
                .steps("{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"{total}\"}")
                .build();
    }

    private UiMcpSession session(DomainStep... steps) {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("order-desk", Paths.get("order-desk")).ready(new PluginParts(() -> Arrays.asList(steps)), "ok");
        return new UiMcpSession(plugins, new UiMcpToolsTest.FakeInventory(), mapper);
    }

    private JsonNode call(UiMcpSession s, String json) throws Exception {
        return mapper.readTree(s.vocabulary(mapper.readTree(json)));
    }

    @Test
    void listShowsTheSentencesAndParams() throws Exception {
        JsonNode r = call(session(addToCart(), cartTotalIs()), "{\"op\":\"list\"}");

        JsonNode steps = r.path("steps");
        assertThat(steps).hasSize(2);
        JsonNode add = steps.get(0);
        assertThat(add.path("id").asText()).isEqualTo("addToCart");
        assertThat(add.path("kind").asText()).isEqualTo("act");
        assertThat(add.path("sentence").asText()).isEqualTo("Add {product} to the cart");
        assertThat(add.path("about").asText()).isEqualTo("Adds a product to the cart of the open new order");
        assertThat(add.path("plugin").asText()).isEqualTo("order-desk");
        assertThat(add.path("params")).hasSize(2);
        assertThat(add.path("params").get(0)).isEqualTo(mapper.readTree(
                "{\"name\":\"product\",\"type\":\"TEXT\",\"optional\":false}"));
        assertThat(add.path("params").get(1)).isEqualTo(mapper.readTree(
                "{\"name\":\"qty\",\"type\":\"INT\",\"optional\":true,\"default\":\"1\"}"));
        assertThat(add.path("folds").asText()).contains("derived").contains("{product}").contains("Add to cart");
        assertThat(add.has("choices")).isFalse();
        assertThat(steps.get(1).path("kind").asText()).isEqualTo("check");
        assertThat(steps.get(1).has("about")).isFalse();
    }

    @Test
    void aStepWhosePatternWasDroppedShowsWhy() throws Exception {
        DomainStep reads = DomainStep.check("totalIsPositive", "The total is above zero")
                .code((ui, p) -> ui.tab("New order*").label("Total").text()).build();

        JsonNode step = call(session(reads), "{\"op\":\"list\"}").path("steps").get(0);

        assertThat(step.path("folds").asText()).startsWith("no pattern: ").contains("reads");
    }

    @Test
    void describeShowsLiveChoices() throws Exception {
        JsonNode r = call(session(addToCart()), "{\"op\":\"describe\",\"step\":\"addToCart\"}");

        assertThat(r.path("id").asText()).isEqualTo("addToCart");
        JsonNode product = r.path("params").get(0);
        assertThat(product.path("choices")).isEqualTo(mapper.readTree("[\"A4 paper, 500 sheets\",\"Gel pen, black\"]"));
        assertThat(r.path("params").get(1).has("choices")).isFalse();
        JsonNode pattern = r.path("patterns").get(0);
        assertThat(pattern.path("derived").asBoolean()).isTrue();
        assertThat(pattern.path("steps")).hasSize(2);
        assertThat(pattern.path("steps").get(0).path("action").asText()).isEqualTo("click");
        assertThat(r.path("body").path("kind").asText()).isEqualTo("code");
        assertThat(r.path("body").path("steps")).hasSize(2);
    }

    @Test
    void aChoicesSupplierThatThrowsDoesNotFailTheAnswer() throws Exception {
        DomainStep broken = DomainStep.act("pickCustomer", "Pick {customer}")
                .param("customer", ParamType.TEXT).choices(() -> { throw new IllegalStateException("no customers yet"); })
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"{customer}\"}}").build();

        JsonNode customer = call(session(broken), "{\"op\":\"describe\",\"step\":\"pickCustomer\"}").path("params").get(0);

        assertThat(customer.has("choices")).isFalse();
        assertThat(customer.path("choicesProblem").asText()).contains("no customers yet");
    }

    @Test
    void aDeclarativeStepShowsItsStepsAsTheBody() throws Exception {
        DomainStep archive = DomainStep.act("archiveOrder", "Archive the open order")
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Archive\"}}").build();

        JsonNode r = call(session(archive), "{\"op\":\"describe\",\"step\":\"archiveOrder\"}");

        assertThat(r.path("body").path("kind").asText()).isEqualTo("steps");
        assertThat(r.path("body").path("steps").get(0).path("target").path("name").asText()).isEqualTo("Archive");
    }

    @Test
    void describeNamesTheStepsThereAreForAnUnknownOne() {
        DriverException e = catchThrowableOfType(() -> call(session(addToCart()), "{\"op\":\"describe\",\"step\":\"nope\"}"),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("nope").contains("addToCart");
    }

    @TempDir Path dir;

    @Test
    void matchIsADryRunOfWhatARecordingRangeWouldFoldInto() throws Exception {
        UiMcpSession s = session(addToCart());
        // a loaded recorded test: its raw steps were never folded
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "add a pen";
        flow.start = RecordedTestFiles.START_RESET;
        String tab = "\"scope\":[\"tab:New order · Ada Shop Ltd\"]";
        for (String step : new String[] {
                "{\"action\":\"click\",\"target\":{" + tab + ",\"role\":\"table\",\"row\":{\"Product\":\"Gel pen, black\"},"
                        + "\"column\":\"Product\"}}",
                "{\"action\":\"click\",\"target\":{" + tab + ",\"role\":\"button\",\"name\":\"Add to cart\"}}",
                "{\"action\":\"click\",\"target\":{" + tab + ",\"role\":\"button\",\"name\":\"Checkout…\"}}"}) {
            flow.steps.add(FlowJson.mapper().readValue(step, io.github.kormcrete.swingui.flow.Action.class));
        }
        Path file = dir.resolve("add-pen.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        s.loadRecordedTest(file);
        JsonNode before = mapper.readTree(s.recording(mapper.readTree("{\"op\":\"list\"}")));

        JsonNode r = call(s, "{\"op\":\"match\",\"from\":1,\"to\":3}");

        assertThat(r.path("folds")).hasSize(1);
        assertThat(r.path("folds").get(0)).isEqualTo(mapper.readTree("{\"from\":1,\"to\":2,\"step\":\"order-desk:addToCart\","
                + "\"params\":{\"product\":\"Gel pen, black\"},\"say\":\"Add Gel pen, black to the cart\"}"));
        assertThat(r.path("notes")).isEmpty();
        // a dry run: the recording is as it was
        assertThat(mapper.readTree(s.recording(mapper.readTree("{\"op\":\"list\"}")))).isEqualTo(before);
        // a range that is no steps is refused
        DriverException e = catchThrowableOfType(() -> call(s, "{\"op\":\"match\",\"from\":2,\"to\":9}"),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void withoutAPluginMatchFoldsNothingWithAHint() throws Exception {
        UiMcpSession plain = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);

        JsonNode r = call(plain, "{\"op\":\"match\",\"from\":1,\"to\":2}");

        assertThat(r.path("folds")).isEmpty();
        assertThat(r.path("hint").asText()).contains("plugins=");
    }

    @Test
    void withoutAPluginListIsEmptyWithAHint() throws Exception {
        UiMcpSession plain = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);

        JsonNode r = call(plain, "{\"op\":\"list\"}");

        assertThat(r.path("steps")).isEmpty();
        assertThat(r.path("hint").asText()).contains("plugins=");
    }

    @Test
    void aPluginWithoutAVocabularyIsEmptyWithAHint() throws Exception {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("order-desk", Paths.get("order-desk")).ready(new PluginParts(null, null), "ok");
        UiMcpSession s = new UiMcpSession(plugins, new UiMcpToolsTest.FakeInventory(), mapper);

        JsonNode r = call(s, "{\"op\":\"list\"}");

        assertThat(r.path("steps")).isEmpty();
        assertThat(r.path("hint").asText()).contains("vocabulary");
    }

    @Test
    void describeUiListsTheVocabularySentences() throws Exception {
        JsonNode v = mapper.readTree(session(addToCart(), cartTotalIs()).describe()).path("vocabulary");

        assertThat(v).hasSize(2);
        assertThat(v.get(0)).isEqualTo(mapper.readTree(
                "{\"id\":\"addToCart\",\"kind\":\"act\",\"sentence\":\"Add {product} to the cart\"}"));
        assertThat(v.get(1).path("id").asText()).isEqualTo("cartTotalIs");
    }

    @Test
    void describeUiVocabularyIsEmptyWithoutPlugins() throws Exception {
        UiMcpSession plain = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        assertThat(mapper.readTree(plain.describe()).path("vocabulary")).isEqualTo(mapper.readTree("[]"));
    }

    @Test
    void aChoicesSupplierThatBlocksIsGivenUpAfterOneSharedLimit() throws Exception {
        DomainStep slow = DomainStep.act("pickCustomer", "Pick {customer} for {contact}")
                .param("customer", ParamType.TEXT).choices(UiMcpVocabularyTest::block)
                .param("contact", ParamType.TEXT).choices(UiMcpVocabularyTest::block)
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"{customer}\"}}").build();
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("order-desk", Paths.get("order-desk")).ready(new PluginParts(() -> Arrays.asList(slow)), "ok");

        long t0 = System.nanoTime();
        JsonNode r = mapper.readTree(new VocabularyTool(plugins, mapper, 150)
                .run(mapper.readTree("{\"op\":\"describe\",\"step\":\"pickCustomer\"}")));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.path("params").get(0).path("choicesProblem").asText()).contains("not ready");
        assertThat(r.path("params").get(1).path("choicesProblem").asText()).contains("not ready");
        assertThat(ms).isLessThan(2000);
    }

    private static java.util.List<String> block() {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Test
    void aThrowWithoutAMessageNamesTheClass() throws Exception {
        DomainStep npe = DomainStep.act("pickCustomer", "Pick {customer}")
                .param("customer", ParamType.TEXT).choices(() -> { throw new IllegalStateException(); })
                .steps("{\"action\":\"noop\"}").build();
        JsonNode customer = call(session(npe), "{\"op\":\"describe\",\"step\":\"pickCustomer\"}").path("params").get(0);
        assertThat(customer.path("choicesProblem").asText()).contains("IllegalStateException");
    }

    @Test
    void describeNeverRunsABody() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        DomainStep written = DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .match("{\"action\":\"click\",\"target\":{\"role\":\"table\",\"row\":{\"Product\":\"{product}\"}}}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}")
                .code((ui, p) -> { runs.incrementAndGet(); ui.button("Add to cart").click(); })
                .build();
        UiMcpSession s = session(written);
        int atReady = runs.get();

        JsonNode r = call(s, "{\"op\":\"describe\",\"step\":\"addToCart\"}");

        assertThat(runs.get()).isEqualTo(atReady);
        assertThat(r.path("body").path("kind").asText()).isEqualTo("code");
        assertThat(r.path("body").path("why").asText()).contains("match(...)");
        assertThat(r.path("patterns").get(0).path("derived").asBoolean()).isFalse();
    }

    @Test
    void aBareIdTwoPluginsShareAsksForThePluginName() {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("order-desk", Paths.get("a")).ready(new PluginParts(() -> Arrays.asList(addToCart())), "ok");
        plugins.add("extras", Paths.get("b")).ready(new PluginParts(() -> Arrays.asList(addToCart())), "ok");
        UiMcpSession s = new UiMcpSession(plugins, new UiMcpToolsTest.FakeInventory(), mapper);

        DriverException e = catchThrowableOfType(() -> call(s, "{\"op\":\"describe\",\"step\":\"addToCart\"}"),
                DriverException.class);

        assertThat(e.getMessage()).contains("more than one plugin").contains("<plugin>:<id>");
    }
}
