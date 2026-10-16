package io.github.kormcrete.swingui.vocab;

import io.github.kormcrete.swingui.agent.BodyTrace;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.Target;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PatternTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DomainStep.Body NOTHING = (ui, p) -> { };

    // ---- Order Desk bodies, on the Client only (as in the vocabulary design)

    static void addToCart(Client ui, String product) {
        Target tab = ui.tab("New order*");
        tab.table().row("Product", product).cell("Product").click();
        tab.button("Add to cart").click();
    }

    static void setQuantity(Client ui, String product, int qty) {
        ui.tab("New order*").table().row("Product", product).cell("Qty").type(String.valueOf(qty), null, true);
    }

    static DomainStep addToCartStep() {
        return DomainStep.act("addToCart", "Add {product} to the cart").param("product", ParamType.TEXT)
                .code((ui, p) -> addToCart(ui, p.text("product"))).build();
    }

    static DomainStep setQuantityStep() {
        return DomainStep.act("setQuantity", "Set the quantity of {product} to {qty}").param("product", ParamType.TEXT)
                .param("qty", ParamType.INT).code((ui, p) -> setQuantity(ui, p.text("product"), p.integer("qty"))).build();
    }

    // ---- subset

    @Test
    void omittedFieldsMatchAnythingAndSomeFieldsAreAlwaysIgnored() {
        Pattern p = only(DomainStep.act("placeOrder", "Place the order").code(NOTHING)
                .match(json("{'action':'click','target':{'role':'button','name':'Place order','nth':2,'window':'main'},"
                        + "'timeout':5000,'fragile':true,'via':'api'}"))
                .build());

        assertThat(p.match(steps("{'action':'click','target':{'window':'dialog','scope':['section:Review'],"
                + "'role':'button','name':'Place order'},'count':1}"))).isNotNull();
        assertThat(p.match(steps("{'action':'click','target':{'role':'button','name':'Place order','nth':0},"
                + "'timeout':100}"))).isNotNull();
        assertThat(p.match(steps("{'action':'click','target':{'role':'button','name':'Checkout…'}}"))).isNull();
        assertThat(p.match(steps("{'action':'click','target':{'name':'Place order'}}"))).isNull();
        assertThat(p.match(steps("{'action':'hover','target':{'role':'button','name':'Place order'}}"))).isNull();
    }

    @Test
    void aPatternMatchesTheWholeRunOfStepsNotMoreNorLess() {
        Pattern p = only(DomainStep.act("placeOrder", "Place the order").code(NOTHING)
                .match(json("{'action':'click','target':{'name':'Place order'}}"), json("{'action':'answer','button':'Place order'}"))
                .build());

        assertThat(p.match(steps("{'action':'click','target':{'name':'Place order'}}",
                "{'action':'answer','button':'Place order'}"))).isNotNull();
        assertThat(p.match(steps("{'action':'click','target':{'name':'Place order'}}"))).isNull();
        assertThat(p.match(steps("{'action':'click','target':{'name':'Place order'}}",
                "{'action':'answer','button':'Place order'}", "{'action':'press','keys':'Enter'}"))).isNull();
    }

    // ---- glob

    @Test
    void aStarMatchesAnyTextAndAScopeIsASubsequence() {
        Pattern p = only(DomainStep.act("checkout", "Check out").code(NOTHING)
                .match(json("{'action':'click','target':{'scope':['tab:New order*'],'name':'Check*'}}")).build());

        assertThat(p.match(steps("{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd',"
                + "'section:Cart'],'role':'button','name':'Checkout…'}}"))).isNotNull();
        assertThat(p.match(steps("{'action':'click','target':{'scope':['section:Cart','tab:New order'],"
                + "'name':'Check'}}"))).isNotNull();
        assertThat(p.match(steps("{'action':'click','target':{'scope':['tab:Orders'],'name':'Checkout…'}}"))).isNull();
        assertThat(p.match(steps("{'action':'click','target':{'name':'Checkout…'}}"))).isNull();
    }

    @Test
    void aBackslashMakesAStarOrABraceText() {
        Pattern p = only(DomainStep.act("name", "Name the order").code(NOTHING)
                .match(json("{'action':'type','target':{'name':'Name \\\\*'},'text':'\\\\{po}'}")).build());

        assertThat(p.match(steps("{'action':'type','target':{'name':'Name *'},'text':'{po}'}"))).isNotNull();
        assertThat(p.match(steps("{'action':'type','target':{'name':'Name of buyer'},'text':'{po}'}"))).isNull();
    }

    // ---- capture

    @Test
    void aParamCapturesTheTextItStandsFor() {
        Pattern p = only(DomainStep.act("openOrder", "Open order {order}").param("order", ParamType.TEXT).code(NOTHING)
                .match(json("{'action':'click','target':{'role':'table','row':{'Order':'{order}'}},'count':2}")).build());

        Pattern.Match m = p.match(steps("{'action':'click','target':{'role':'table','name':'Orders',"
                + "'row':{'Order':'ORD-1640'}},'count':2}"));

        assertThat(m).isNotNull();
        assertThat(m.params()).containsExactly(entry("order", "ORD-1640"));
    }

    @Test
    void aCaptureTakesAsLittleAsItCanAndNeverNothing() {
        Pattern p = only(DomainStep.act("note", "Note {first} then {rest}").param("first", ParamType.TEXT)
                .param("rest", ParamType.TEXT).code(NOTHING)
                .match(json("{'action':'type','target':{'name':'Note'},'text':'{first} {rest}'}")).build());

        assertThat(p.match(steps("{'action':'type','target':{'name':'Note'},'text':'Gel pen, black'}")).params())
                .containsExactly(entry("first", "Gel"), entry("rest", "pen, black"));
        assertThat(p.match(steps("{'action':'type','target':{'name':'Note'},'text':' x'}"))).isNull();
    }

    @Test
    void aCaptureRightBeforeAStarTakesTheWholeTitle() {
        DomainStep openOrder = DomainStep.act("openOrder", "Open order {order}").param("order", ParamType.TEXT)
                .code((ui, p) -> ui.tab(p.text("order") + "*").button("Print").click()).build();

        Pattern p = compile(openOrder).get(0).patterns().get(0);

        assertThat(p.match(steps("{'action':'click','target':{'scope':['tab:ORD-1640 · Ada Shop Ltd'],'role':'button',"
                + "'name':'Print'}}")).params()).containsExactly(entry("order", "ORD-1640 · Ada Shop Ltd"));
    }

    @Test
    void aLaterUseOfTheSameParamNarrowsACaptureBeforeAStar() {
        DomainStep openOrder = DomainStep.act("openOrder", "Open order {order}").param("order", ParamType.TEXT)
                .code((ui, p) -> ui.tab(p.text("order") + "*").table().row("Order", p.text("order")).click()).build();

        Pattern p = compile(openOrder).get(0).patterns().get(0);

        assertThat(p.match(steps("{'action':'click','target':{'scope':['tab:ORD-1640 · Ada Shop Ltd'],'role':'table',"
                + "'row':{'Order':'ORD-1640'}}}")).params()).containsExactly(entry("order", "ORD-1640"));
        assertThat(p.match(steps("{'action':'click','target':{'scope':['tab:ORD-1640 · Ada Shop Ltd'],'role':'table',"
                + "'row':{'Order':'ORD-1641'}}}"))).isNull();
    }

    @Test
    void aWrittenCaptureBeforeAStarIsNarrowedByALaterStep() {
        Pattern p = only(DomainStep.act("notePo", "Note PO {po}").param("po", ParamType.TEXT).code(NOTHING)
                .match(json("{'action':'type','target':{'name':'Note'},'text':'{po}*'}"),
                        json("{'action':'type','target':{'name':'PO number'},'text':'{po}'}"))
                .build());
        Pattern alone = only(DomainStep.act("notePo", "Note PO {po}").param("po", ParamType.TEXT).code(NOTHING)
                .match(json("{'action':'type','target':{'name':'Note'},'text':'{po}*'}")).build());

        assertThat(alone.match(steps("{'action':'type','target':{'name':'Note'},'text':'PO-7781 urgent'}")).params())
                .containsExactly(entry("po", "PO-7781 urgent"));
        assertThat(p.match(steps("{'action':'type','target':{'name':'Note'},'text':'PO-7781 urgent'}",
                "{'action':'type','target':{'name':'PO number'},'text':'PO-7781'}")).params())
                .containsExactly(entry("po", "PO-7781"));
    }

    // ---- the same param twice

    @Test
    void theSameParamCapturedTwiceMustBeEqual() {
        Pattern p = only(DomainStep.act("addToCart", "Add {product} to the cart").param("product", ParamType.TEXT)
                .code(NOTHING)
                .match(json("{'action':'click','target':{'row':{'Product':'{product}'}}}"),
                        json("{'action':'type','target':{'row':{'Product':'{product}'},'column':'Qty'},'text':'1'}"))
                .build());

        assertThat(p.match(steps("{'action':'click','target':{'row':{'Product':'Gel pen, black'}}}",
                "{'action':'type','target':{'row':{'Product':'Gel pen, black'},'column':'Qty'},'text':'1'}")).params())
                .containsExactly(entry("product", "Gel pen, black"));
        assertThat(p.match(steps("{'action':'click','target':{'row':{'Product':'Gel pen, black'}}}",
                "{'action':'type','target':{'row':{'Product':'A4 paper, 500 sheets'},'column':'Qty'},'text':'1'}")))
                .isNull();
    }

    @Test
    void theSameParamTwiceInOneTextMustBeEqualToo() {
        Pattern p = only(DomainStep.act("twice", "Say {word} twice").param("word", ParamType.TEXT).code(NOTHING)
                .match(json("{'action':'type','text':'{word}-{word}'}")).build());

        assertThat(p.match(steps("{'action':'type','text':'pen-pen'}")).params()).containsExactly(entry("word", "pen"));
        assertThat(p.match(steps("{'action':'type','text':'pen-ink'}"))).isNull();
    }

    // ---- INT

    @Test
    void anIntParamCapturesDigitsOnly() {
        Pattern p = only(setQuantity("{'action':'type','target':{'row':{'Product':'{product}'},'column':'Qty'},"
                + "'text':'{qty}'}"));

        assertThat(p.match(steps("{'action':'type','target':{'row':{'Product':'Gel pen, black'},'column':'Qty'},"
                + "'text':'10'}")).params()).containsExactly(entry("product", "Gel pen, black"), entry("qty", "10"));
        assertThat(p.match(steps("{'action':'type','target':{'row':{'Product':'Gel pen, black'},'column':'Qty'},"
                + "'text':'ten'}"))).isNull();
        assertThat(p.match(steps("{'action':'type','target':{'row':{'Product':'Gel pen, black'},'column':'Qty'},"
                + "'text':'-3'}"))).isNull();
        assertThat(p.match(steps("{'action':'type','target':{'row':{'Product':'Gel pen, black'},'column':'Qty'},"
                + "'text':'99999999999'}"))).isNull();
    }

    @Test
    void anIntParamThatIsTheWholeTextMatchesANumberToo() {
        Pattern p = only(DomainStep.act("openRow", "Open order row {row}").param("row", ParamType.INT).code(NOTHING)
                .match(json("{'action':'click','target':{'role':'table','row':{'index':'{row}'}},'count':2}")).build());

        assertThat(p.match(steps("{'action':'click','target':{'role':'table','row':{'index':3}},'count':2}")).params())
                .containsExactly(entry("row", "3"));
        assertThat(p.match(steps("{'action':'click','target':{'role':'table','row':{'index':-1}},'count':2}"))).isNull();
        assertThat(p.match(steps("{'action':'click','target':{'role':'table','row':{'index':3.5}},'count':2}"))).isNull();
        // a number in the pattern is a number in the step
        assertThat(p.match(steps("{'action':'click','target':{'role':'table','row':{'index':3}},'count':'2'}"))).isNull();
    }

    @Test
    void aTextParamThatIsTheWholeTextMatchesNoNumber() {
        Pattern p = only(DomainStep.act("openRow", "Open order row {row}").param("row", ParamType.TEXT).code(NOTHING)
                .match(json("{'action':'click','target':{'row':{'index':'{row}'}}}")).build());

        assertThat(p.match(steps("{'action':'click','target':{'row':{'index':3}}}"))).isNull();
    }

    // ---- optional

    @Test
    void anOptionalStepMayBeLeftOut() {
        Pattern p = only(DomainStep.act("openOrder", "Open order {order}").param("order", ParamType.TEXT).code(NOTHING)
                .match(json("{'optional':true,'action':'click','target':{'role':'tab','name':'Orders'}}"),
                        json("{'action':'click','target':{'row':{'Order':'{order}'}},'count':2}"))
                .build());

        assertThat(p.match(steps("{'action':'click','target':{'role':'tab','name':'Orders'}}",
                "{'action':'click','target':{'row':{'Order':'ORD-1640'}},'count':2}")).params())
                .containsExactly(entry("order", "ORD-1640"));
        assertThat(p.match(steps("{'action':'click','target':{'row':{'Order':'ORD-1640'}},'count':2}")).params())
                .containsExactly(entry("order", "ORD-1640"));
        assertThat(p.match(steps("{'action':'click','target':{'role':'tab','name':'Orders'}}"))).isNull();
    }

    @Test
    void anOptionalParamLeftUncapturedIsLeftOut() {
        Pattern p = only(DomainStep.act("openOrder", "Open order {order} in {view}").param("order", ParamType.TEXT)
                .param("view", ParamType.TEXT).optional("Orders").code(NOTHING)
                .match(json("{'optional':true,'action':'click','target':{'role':'tab','name':'{view}'}}"),
                        json("{'action':'click','target':{'row':{'Order':'{order}'}},'count':2}"))
                .build());

        assertThat(p.match(steps("{'action':'click','target':{'row':{'Order':'ORD-1640'}},'count':2}")).params())
                .containsExactly(entry("order", "ORD-1640"));
    }

    // ---- use

    @Test
    void useReusesAnotherStepsPatternWithTheSameParams() {
        DomainStep addQtyToCart = DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
                .param("product", ParamType.TEXT).param("qty", ParamType.INT).code(NOTHING)
                .match("{\"use\":\"addToCart\"}", "{\"use\":\"setQuantity\"}").build();
        List<PatternCompiler.Compiled> all = compile(addToCartStep(), setQuantityStep(), addQtyToCart);
        Pattern p = all.get(2).patterns().get(0);

        assertThat(all.get(2).problems()).isEmpty();
        Pattern.Match m = p.match(steps(
                "{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd'],'role':'table','name':'Products',"
                        + "'row':{'Product':'Gel pen, black'},'column':'Product'}}",
                "{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd'],'role':'button','name':'Add to cart'}}",
                "{'action':'type','target':{'scope':['tab:New order · Ada Shop Ltd'],'role':'table','name':'Cart',"
                        + "'row':{'Product':'Gel pen, black'},'column':'Qty'},'text':'10','commit':true}"));
        assertThat(m).isNotNull();
        assertThat(m.params()).containsExactly(entry("product", "Gel pen, black"), entry("qty", "10"));
        assertThat(p.match(steps(
                "{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd'],'role':'table',"
                        + "'row':{'Product':'Gel pen, black'},'column':'Product'}}",
                "{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd'],'role':'button','name':'Add to cart'}}",
                "{'action':'type','target':{'scope':['tab:New order · Ada Shop Ltd'],'role':'table',"
                        + "'row':{'Product':'A4 paper, 500 sheets'},'column':'Qty'},'text':'10','commit':true}")))
                .isNull();
    }

    @Test
    void anOptionalUseMayBeLeftOutAsAWhole() {
        DomainStep openOrders = DomainStep.act("openOrders", "Open the orders").code(NOTHING)
                .match(json("{'action':'menu','path':['View','Orders']}"), json("{'action':'click','target':{'role':'tab','name':'Orders'}}"))
                .build();
        DomainStep openOrder = DomainStep.act("openOrder", "Open order {order}").param("order", ParamType.TEXT).code(NOTHING)
                .match("{\"use\":\"openOrders\",\"optional\":true}",
                        json("{'action':'click','target':{'row':{'Order':'{order}'}},'count':2}"))
                .build();
        Pattern p = compile(openOrders, openOrder).get(1).patterns().get(0);

        assertThat(p.match(steps("{'action':'menu','path':['View','Orders']}",
                "{'action':'click','target':{'role':'tab','name':'Orders'}}",
                "{'action':'click','target':{'row':{'Order':'ORD-1640'}},'count':2}"))).isNotNull();
        assertThat(p.match(steps("{'action':'click','target':{'row':{'Order':'ORD-1640'}},'count':2}"))).isNotNull();
        assertThat(p.match(steps("{'action':'menu','path':['View','Orders']}",
                "{'action':'click','target':{'row':{'Order':'ORD-1640'}},'count':2}"))).isNull();
    }

    // ---- where a step's patterns come from

    @Test
    void aJavaBodyIsItsOwnPatternThroughItsTrace() {
        PatternCompiler.Compiled c = compile(addToCartStep()).get(0);

        assertThat(c.why()).isNull();
        assertThat(c.patterns()).hasSize(1);
        assertThat(c.patterns().get(0).derived()).isTrue();
        assertThat(c.patterns().get(0).match(steps(
                "{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd','section:Products'],'role':'table',"
                        + "'name':'Products','row':{'Product':'A4 paper, 500 sheets'},'column':'Product'}}",
                "{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd'],'role':'button','name':'Add to cart'}}"))
                .params()).containsExactly(entry("product", "A4 paper, 500 sheets"));
    }

    @Test
    void anIntParamTracedIntoANumberFieldMatchesANumber() {
        DomainStep openRow = DomainStep.act("openRow", "Open order row {row}").param("row", ParamType.INT)
                .code((ui, p) -> ui.table("Orders").rowAt(p.integer("row")).click("left", 2)).build();

        Pattern p = compile(openRow).get(0).patterns().get(0);

        assertThat(p.match(steps("{'action':'click','target':{'role':'table','name':'Orders','row':{'index':4}},"
                + "'button':'left','count':2}")).params()).containsExactly(entry("row", "4"));
    }

    @Test
    void aDeclarativeBodyIsItsOwnPattern() {
        DomainStep openOrder = DomainStep.act("openOrder", "Open order {order}").param("order", ParamType.TEXT)
                .steps(json("{'action':'click','target':{'role':'tab','name':'Orders'}}"),
                        json("{'action':'click','target':{'role':'table','row':{'Order':'{order}'}},'count':2}"))
                .build();

        PatternCompiler.Compiled c = compile(openOrder).get(0);

        assertThat(c.patterns()).hasSize(1);
        assertThat(c.patterns().get(0).derived()).isTrue();
        assertThat(c.patterns().get(0).match(steps("{'action':'click','target':{'role':'tab','name':'Orders'}}",
                "{'action':'click','target':{'role':'table','name':'Orders','row':{'Order':'ORD-1640'}},'count':2}"))
                .params()).containsExactly(entry("order", "ORD-1640"));
    }

    @Test
    void inADerivedPatternAStarAndAnUndeclaredBraceAreText() {
        DomainStep fill = DomainStep.act("fillName", "Fill the name with {name}").param("name", ParamType.TEXT)
                .code((ui, p) -> ui.tab("New order*").field("Name *").fill(p.text("name") + " {note}")).build();

        Pattern p = compile(fill).get(0).patterns().get(0);

        assertThat(p.match(steps("{'action':'type','target':{'scope':['tab:New order · Birch & Co'],'role':'text',"
                + "'name':'Name *'},'text':'Ada {note}','replace':true,'commit':true}")).params())
                .containsExactly(entry("name", "Ada"));
        assertThat(p.match(steps("{'action':'type','target':{'scope':['tab:New order · Birch & Co'],'role':'text',"
                + "'name':'Name of buyer'},'text':'Ada {note}','replace':true,'commit':true}"))).isNull();
        assertThat(p.match(steps("{'action':'type','target':{'scope':['tab:New order · Birch & Co'],'role':'text',"
                + "'name':'Name *'},'text':'Ada x','replace':true,'commit':true}"))).isNull();
    }

    @Test
    void matchReplacesTheDerivedPatternAndMatchAlsoAddsAnother() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart").param("product", ParamType.TEXT)
                .code((ui, p) -> {
                    throw new IllegalStateException("never traced");
                })
                .match(json("{'action':'click','target':{'row':{'Product':'{product}'}}}"),
                        json("{'action':'click','target':{'name':'Add to cart'}}"))
                .matchAlso(json("{'action':'contextMenu','target':{'row':{'Product':'{product}'}},'path':['Add to cart']}"))
                .build();

        PatternCompiler.Compiled c = compile(step).get(0);

        assertThat(c.why()).isNull();
        assertThat(c.patterns()).hasSize(2);
        assertThat(c.patterns().get(0).derived()).isFalse();
        assertThat(c.patterns().get(1).match(steps("{'action':'contextMenu','target':{'role':'table',"
                + "'row':{'Product':'Gel pen, black'}},'path':['Add to cart']}")).params())
                .containsExactly(entry("product", "Gel pen, black"));
    }

    @Test
    void aBodyWithoutAPatternSaysWhyAndKeepsItsOtherPatterns() {
        DomainStep step = DomainStep.act("placeOrder", "Place the order")
                .code((ui, p) -> ui.label("Status").text())
                .matchAlso(json("{'action':'click','target':{'name':'Place order'}}"))
                .build();

        PatternCompiler.Compiled c = compile(step).get(0);

        assertThat(c.problems()).isEmpty();
        assertThat(c.why()).startsWith("the body reads the app");
        assertThat(c.patterns()).hasSize(1);
        assertThat(c.patterns().get(0).derived()).isFalse();
    }

    @Test
    void aDerivedPatternThatMissesARequiredParamIsNoPattern() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart").param("product", ParamType.TEXT)
                .code((ui, p) -> ui.button("Add to cart").click()).build();
        DomainStep byKey = DomainStep.act("openOrder", "Open ORD-1640 by {column}").param("column", ParamType.TEXT)
                .code((ui, p) -> ui.table("Orders").row(p.text("column"), "ORD-1640").click()).build();
        DomainStep none = DomainStep.act("wait", "Wait").code(NOTHING).build();

        List<PatternCompiler.Compiled> c = compile(step, byKey, none);

        assertThat(c.get(0).patterns()).isEmpty();
        assertThat(c.get(0).why()).isEqualTo("the pattern captures no {product}, a required param");
        assertThat(c.get(1).patterns()).isEmpty();
        assertThat(c.get(1).why()).isEqualTo("a param stands in a field name ('{column}'), which a pattern does not match");
        assertThat(c.get(2).patterns()).isEmpty();
        assertThat(c.get(2).why()).isEqualTo("the body makes no steps");
        assertThat(c.get(0).problems()).isEmpty();
    }

    // ---- what does not parse

    @Test
    void anExplicitPatternThatDoesNotParseHasProblems() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart").param("product", ParamType.TEXT)
                .code(NOTHING)
                .match(json("{'target':{'name':'Add to cart'}}"),
                        json("{'action':'click','target':{'row':{'Product':'{prodcut}'}}}"),
                        json("{'action':'click','optional':'yes'}"),
                        json("{'action':'click','target':{'name':'a\\\\b'}}"),
                        json("{'action':'click','target':{'row':{'{product}':'x'}}}"))
                .matchAlso("{\"use\":\"nowhere\"}")
                .matchAlso("{\"use\":\"addToCart\",\"action\":\"click\"}")
                .matchAlso(json("{'optional':true,'action':'click'}"))
                .matchAlso()
                .matchAlso(json("{'action':'click'}"))
                .build();

        assertThat(compile(step).get(0).problems()).containsExactly(
                "match entry 1 has no action",
                "match entry 2 names {prodcut}, which is not a declared param",
                "match entry 3: optional is true or false",
                "match entry 4: a \\ makes only *, { or \\ text: 'a\\b'",
                "match entry 5: a field name holds a {param}: '{product}'",
                "matchAlso 1 entry 1 uses 'nowhere', which is no step of this plugin",
                "matchAlso 2 entry 1: a use entry holds only use and optional",
                "matchAlso 3: every entry is optional, so it would match no steps",
                "matchAlso 4 is empty",
                "matchAlso 5 captures no {product}, a required param");
    }

    @Test
    void usesThatGoRoundInACircleAreAProblem() {
        DomainStep a = DomainStep.act("a", "A").code(NOTHING).match("{\"use\":\"b\"}").build();
        DomainStep b = DomainStep.act("b", "B").code(NOTHING).match("{\"use\":\"a\"}").build();
        DomainStep c = DomainStep.act("c", "C").code(NOTHING).match("{\"use\":\"c\"}").build();
        DomainStep d = DomainStep.act("d", "D").code(NOTHING).match("{\"use\":\"wait\"}").build();
        DomainStep wait = DomainStep.act("wait", "Wait").code(NOTHING).build();

        List<PatternCompiler.Compiled> all = compile(a, b, c, d, wait);

        assertThat(all.get(0).problems()).containsExactly("match entry 1 uses 'b' in a circle: a → b → a");
        assertThat(all.get(1).problems()).containsExactly("match entry 1 uses 'a' in a circle: b → a → b");
        assertThat(all.get(2).problems()).containsExactly("match entry 1 uses 'c' in a circle: c → c");
        assertThat(all.get(3).problems()).containsExactly("match entry 1 uses 'wait', which has no pattern (the body makes no steps)");
        assertThat(all.get(4).problems()).isEmpty();
    }

    // ---- helpers

    private static DomainStep setQuantity(String pattern) {
        return DomainStep.act("setQuantity", "Set the quantity of {product} to {qty}").param("product", ParamType.TEXT)
                .param("qty", ParamType.INT).code(NOTHING).match(json(pattern)).build();
    }

    /** The step's only pattern; fails when the step has problems. */
    private static Pattern only(DomainStep step) {
        PatternCompiler.Compiled c = compile(step).get(0);
        assertThat(c.problems()).isEmpty();
        assertThat(c.patterns()).hasSize(1);
        return c.patterns().get(0);
    }

    static List<PatternCompiler.Compiled> compile(DomainStep... steps) {
        return PatternCompiler.compile(Arrays.asList(steps), s -> {
            BodyTrace.Result r = BodyTrace.of(s);
            return r.derivable() ? PatternCompiler.Traced.steps(r.steps()) : PatternCompiler.Traced.none(r.why());
        });
    }

    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    private static List<JsonNode> steps(String... singleQuoted) {
        List<JsonNode> out = new ArrayList<JsonNode>();
        for (String s : singleQuoted) {
            try {
                out.add(JSON.readTree(json(s)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Map.Entry<String, String> entry(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m.entrySet().iterator().next();
    }
}
