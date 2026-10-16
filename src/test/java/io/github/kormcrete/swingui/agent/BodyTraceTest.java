package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.Target;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static io.github.kormcrete.swingui.junit.UiAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyTraceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A copy of Order Desk's addToCart from the vocabulary design: on the Client only, no app classes. */
    static void addToCart(Client ui, String product) {
        Target tab = ui.tab("New order*");
        tab.table().row("Product", product).cell("Product").click();
        tab.button("Add to cart").click();
    }

    static void setQuantity(Client ui, String product, int qty) {
        ui.tab("New order*").table().row("Product", product).cell("Qty").type(String.valueOf(qty), null, true);
    }

    @Test
    void theOrderDeskAddToCartBodyTracesToTwoSteps() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> addToCart(ui, p.text("product")))
                .build();

        BodyTrace.Result trace = BodyTrace.of(step);

        assertThat(trace.why()).isNull();
        assertThat(trace.derivable()).isTrue();
        assertThat(steps(trace)).isEqualTo(json("["
                + "{'action':'click','target':{'scope':['tab:New order*'],'role':'table','row':{'Product':'{product}'},"
                + "'column':'Product'}},"
                + "{'action':'click','target':{'scope':['tab:New order*'],'role':'button','name':'Add to cart'}}]"));
    }

    @Test
    void anIntParamIsMappedBack() {
        DomainStep step = DomainStep.act("setQuantity", "Set the quantity of {product} to {qty}")
                .param("product", ParamType.TEXT)
                .param("qty", ParamType.INT)
                .code((ui, p) -> setQuantity(ui, p.text("product"), p.integer("qty")))
                .build();

        BodyTrace.Result trace = BodyTrace.of(step);

        assertThat(trace.derivable()).isTrue();
        assertThat(steps(trace)).isEqualTo(json("[{'action':'type','target':{'scope':['tab:New order*'],'role':'table',"
                + "'row':{'Product':'{product}'},'column':'Qty'},'text':'{qty}','commit':true}]"));
    }

    @Test
    void anIntParamInANumberFieldIsMappedBack() {
        DomainStep step = DomainStep.act("openRow", "Open order row {row}")
                .param("row", ParamType.INT)
                .code((ui, p) -> ui.table("Orders").rowAt(p.integer("row")).click("left", 2))
                .build();

        BodyTrace.Result trace = BodyTrace.of(step);

        assertThat(trace.derivable()).isTrue();
        assertThat(steps(trace)).isEqualTo(json("[{'action':'click','target':{'role':'table','name':'Orders',"
                + "'row':{'index':'{row}'}},'button':'left','count':2}]"));
        assertThat(trace.numberPlaceholders()).containsExactly("/0/target/row/index");
    }

    @Test
    void aPlaceholderInTextIsNoNumberPlaceholder() {
        DomainStep step = DomainStep.act("setQuantity", "Set the quantity of {product} to {qty}")
                .param("product", ParamType.TEXT)
                .param("qty", ParamType.INT)
                .code((ui, p) -> setQuantity(ui, p.text("product"), p.integer("qty")))
                .build();

        assertThat(BodyTrace.of(step).numberPlaceholders()).isEmpty();
    }

    @Test
    void concreteValuesGiveStepsAsDoActionTakesThem() {
        DomainStep step = DomainStep.act("openRow", "Open order row {row} of {customer}")
                .param("row", ParamType.INT)
                .param("customer", ParamType.TEXT).optional("Ada Shop Ltd")
                .code((ui, p) -> ui.tab("Orders · " + p.text("customer")).table().rowAt(p.integer("row")).click("left", 2))
                .build();

        BodyTrace.Result trace = BodyTrace.steps(step, Collections.singletonMap("row", "3"));

        assertThat(trace.derivable()).isTrue();
        assertThat(trace.numberPlaceholders()).isEmpty();
        assertThat(steps(trace)).isEqualTo(json("[{'action':'click','target':{'scope':['tab:Orders · Ada Shop Ltd'],"
                + "'role':'table','row':{'index':3}},'button':'left','count':2}]"));
        assertThat(trace.steps().get(0).at("/target/row/index").isInt()).isTrue();
    }

    @Test
    void concreteValuesStillRefuseTheRunId() {
        DomainStep step = DomainStep.act("nameOrder", "Name the order")
                .code((ui, p) -> ui.field("Reference").fill("Order " + ui.runId()))
                .build();

        assertThat(BodyTrace.steps(step, Collections.<String, String>emptyMap()).why()).isEqualTo(BodyTrace.DEPENDS_ON_VALUES);
    }

    @Test
    void aSecretIsNeverKept() {
        DomainStep step = DomainStep.act("logIn", "Log in as the clerk")
                .code((ui, p) -> ui.password("Password").fill(ui.secret("clerk", "password")))
                .build();

        BodyTrace.Result pattern = BodyTrace.of(step);
        BodyTrace.Result concrete = BodyTrace.steps(step, Collections.<String, String>emptyMap());

        assertThat(pattern.derivable()).isFalse();
        assertThat(pattern.steps()).isEmpty();
        assertThat(pattern.why()).isEqualTo("the body reads a secret, which a trace must not keep");
        assertThat(concrete.why()).isEqualTo("the body reads a secret, which a trace must not keep");
    }

    @Test
    void aTextParamInsideALongerStringIsMappedBack() {
        DomainStep step = DomainStep.act("reference", "Note the order for {customer}")
                .param("customer", ParamType.TEXT)
                .code((ui, p) -> ui.field("Reference").fill("Order for " + p.text("customer")))
                .build();

        assertThat(steps(BodyTrace.of(step))).isEqualTo(json("[{'action':'type','target':{'role':'text',"
                + "'name':'Reference'},'text':'Order for {customer}','replace':true,'commit':true}]"));
    }

    @Test
    void anIntParamInsideALongerStringIsMappedBack() {
        DomainStep step = DomainStep.act("note", "Note {qty} pieces")
                .param("qty", ParamType.INT)
                .code((ui, p) -> ui.field("Note").fill("Qty " + p.integer("qty") + " pcs"))
                .build();

        assertThat(steps(BodyTrace.of(step)).at("/0/text").asText()).isEqualTo("Qty {qty} pcs");
    }

    @Test
    void aParamAsARowKeyIsMappedBack() {
        DomainStep step = DomainStep.act("openOrder", "Open ORD-1640 by {column}")
                .param("column", ParamType.TEXT)
                .code((ui, p) -> ui.table("Orders").row(p.text("column"), "ORD-1640").click())
                .build();

        assertThat(steps(BodyTrace.of(step))).isEqualTo(json("[{'action':'click','target':{'role':'table','name':'Orders',"
                + "'row':{'{column}':'ORD-1640'}}}]"));
    }

    @Test
    void aBodyThatFailsOnlyForTheSecondValuesDependsOnThem() {
        DomainStep step = DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
                .param("product", ParamType.TEXT)
                .param("qty", ParamType.INT)
                .code((ui, p) -> {
                    if (p.integer("qty") == 1) throw new IllegalStateException("one is not enough");
                    addToCart(ui, p.text("product"));
                })
                .build();

        assertThat(BodyTrace.of(step).why()).isEqualTo(BodyTrace.DEPENDS_ON_VALUES);
    }

    /** A body that waits for ever, until its thread is interrupted. */
    private static final DomainStep.Body WAITS = (ui, p) -> {
        try {
            new java.util.concurrent.CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    @Test
    void aBodyThatDoesNotEndIsStoppedAtTheTimeLimit() {
        DomainStep step = DomainStep.act("waitForStock", "Wait for {product}").param("product", ParamType.TEXT)
                .code(WAITS).build();

        long start = System.nanoTime();
        BodyTrace.Result pattern = BodyTrace.of(step, java.time.Duration.ofMillis(200));
        BodyTrace.Result concrete = BodyTrace.steps(step, Collections.singletonMap("product", "Gel pen, black"),
                java.time.Duration.ofMillis(200));

        assertThat(System.nanoTime() - start).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        assertThat(pattern.derivable()).isFalse();
        assertThat(pattern.why()).isEqualTo("the body did not end within 200 ms: it waits, loops or blocks");
        assertThat(concrete.why()).isEqualTo("the body did not end within 200 ms: it waits, loops or blocks");
    }

    @Test
    void withinTheTimeLimitATraceIsAsWithoutOne() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> addToCart(ui, p.text("product")))
                .build();

        assertThat(steps(BodyTrace.of(step, BodyTrace.TIME_LIMIT))).isEqualTo(steps(BodyTrace.of(step)));
        assertThatThrownBy(() -> BodyTrace.steps(step, Collections.<String, String>emptyMap(), BodyTrace.TIME_LIMIT))
                .isInstanceOf(DriverException.class).hasMessageContaining("needs param 'product'");
    }

    @Test
    void aCheckedExceptionOrAStackOverflowIsAFailedBody() {
        DomainStep sneaky = DomainStep.act("sneaky", "Throw a checked exception")
                .code((ui, p) -> BodyTraceTest.<RuntimeException>sneakyThrow(new java.io.IOException("disk gone")))
                .build();
        DomainStep deep = DomainStep.act("deep", "Recurse for ever")
                .code((ui, p) -> recurse(0))
                .build();

        assertThat(BodyTrace.of(sneaky).why()).isEqualTo("the body failed: disk gone");
        assertThat(BodyTrace.of(deep).why()).startsWith("the body failed: java.lang.StackOverflowError");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private static int recurse(int n) {
        return recurse(n + 1) + 1;
    }

    @Test
    void aBranchOnAParamIsNotDerivable() {
        DomainStep step = DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
                .param("product", ParamType.TEXT)
                .param("qty", ParamType.INT)
                .code((ui, p) -> {
                    addToCart(ui, p.text("product"));
                    if (p.integer("qty") > 1) setQuantity(ui, p.text("product"), p.integer("qty"));
                })
                .build();

        BodyTrace.Result trace = BodyTrace.of(step);

        assertThat(trace.derivable()).isFalse();
        assertThat(trace.steps()).isEmpty();
        assertThat(trace.why()).isEqualTo(BodyTrace.DEPENDS_ON_VALUES);
    }

    @Test
    void aBranchOnATextParamIsNotDerivable() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> addToCart(ui, p.text("product").split(",")[0]))
                .build();

        assertThat(BodyTrace.of(step).why()).isEqualTo(BodyTrace.DEPENDS_ON_VALUES);
    }

    @Test
    void theRunIdIsAValueToo() {
        DomainStep step = DomainStep.act("nameOrder", "Name the order")
                .code((ui, p) -> ui.field("Reference").fill("Order " + ui.runId()))
                .build();

        assertThat(BodyTrace.of(step).why()).isEqualTo(BodyTrace.DEPENDS_ON_VALUES);
    }

    @Test
    void aCheckingBodyIsNotDerivable() {
        DomainStep step = DomainStep.check("cartTotalIs", "The cart total is {total}")
                .param("total", ParamType.TEXT)
                .code((ui, p) -> assertThat(ui.label("Cart total")).hasText(p.text("total")))
                .build();

        BodyTrace.Result trace = BodyTrace.of(step);

        assertThat(trace.derivable()).isFalse();
        assertThat(trace.why()).isEqualTo("the body checks the app (expect)");
    }

    @Test
    void concreteValuesAreCheckedAsDoActionChecksThem() {
        DomainStep step = DomainStep.act("setQuantity", "Set the quantity of {product} to {qty}")
                .param("product", ParamType.TEXT)
                .param("qty", ParamType.INT)
                .code((ui, p) -> setQuantity(ui, p.text("product"), p.integer("qty")))
                .build();

        assertThatThrownBy(() -> BodyTrace.steps(step, Collections.singletonMap("product", "Gel pen, black")))
                .isInstanceOf(DriverException.class)
                .hasMessage("BAD_INPUT: setQuantity needs param 'qty'");
        java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
        values.put("product", "Gel pen, black");
        values.put("qty", "three");
        assertThatThrownBy(() -> BodyTrace.steps(step, values))
                .hasMessage("BAD_INPUT: param 'qty' of setQuantity is INT: a whole number in int range, not 'three'");
        values.put("qty", "3");
        values.put("colour", "black");
        assertThatThrownBy(() -> BodyTrace.steps(step, values))
                .hasMessage("BAD_INPUT: setQuantity has no param 'colour' (its params: product, qty)");
    }

    @Test
    void aBodyReadingATextParamAsANumberSaysWhichParam() {
        DomainStep step = DomainStep.act("setQuantity", "Set the quantity of {product}")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> setQuantity(ui, "Gel pen, black", p.integer("product")))
                .build();

        assertThat(BodyTrace.of(step).why())
                .isEqualTo("the body failed: param 'product' of setQuantity is TEXT, not INT: read it with text(\"product\")");
    }

    @Test
    void aFailingBodySaysWhy() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> addToCart(ui, p.text("produkt")))
                .build();

        assertThat(BodyTrace.of(step).why()).isEqualTo("the body failed: addToCart has no param 'produkt'");
    }

    private static JsonNode steps(BodyTrace.Result trace) {
        return JSON.valueToTree(trace.steps());
    }

    private static JsonNode json(String singleQuoted) {
        try {
            return JSON.readTree(singleQuoted.replace('\'', '"'));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
