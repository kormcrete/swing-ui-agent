package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.Target;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static io.github.kormcrete.swingui.junit.UiAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

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
    void aFailingBodySaysWhy() {
        DomainStep step = DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> addToCart(ui, p.text("produkt")))
                .build();

        assertThat(BodyTrace.of(step).why()).isEqualTo("the body failed: the step has no param 'produkt'");
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
