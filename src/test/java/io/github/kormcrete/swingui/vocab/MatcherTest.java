package io.github.kormcrete.swingui.vocab;

import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Folding a recording into domain steps, on Order Desk recordings ({@code src/test/resources/vocab/}). */
class MatcherTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DomainStep.Body NOTHING = (ui, p) -> { };
    private static final String NEW_ORDER_TAB = "'scope':['tab:New order*']";

    // ---- the Order Desk vocabulary (design: "Order Desk demo plugin"), its bodies as steps

    private static final DomainStep OPEN_NEW_ORDER = DomainStep.act("openNewOrder", "Open a new order for {customer}")
            .param("customer", ParamType.TEXT)
            .steps(json("{'action':'menu','path':['File','New order']}"),
                    json("{'action':'type','target':{" + NEW_ORDER_TAB + ",'role':'text','name':'Customer'},'text':'{customer}'}"),
                    json("{'action':'press','keys':'Enter'}"))
            .build();
    private static final DomainStep ADD_TO_CART = DomainStep.act("addToCart", "Add {product} to the cart")
            .param("product", ParamType.TEXT)
            .steps(json("{'action':'click','target':{" + NEW_ORDER_TAB + ",'role':'table','row':{'Product':'{product}'}}}"),
                    json("{'action':'click','target':{" + NEW_ORDER_TAB + ",'role':'button','name':'Add to cart'}}"))
            .build();
    private static final DomainStep SET_QUANTITY = DomainStep.act("setQuantity", "Set the quantity of {product} to {qty}")
            .param("product", ParamType.TEXT).param("qty", ParamType.INT)
            .steps(json("{'action':'type','target':{" + NEW_ORDER_TAB + ",'role':'table','row':{'Product':'{product}'},"
                    + "'column':'Qty'},'text':'{qty}','commit':true}"))
            .build();
    private static final DomainStep ADD_QTY_TO_CART = DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
            .param("product", ParamType.TEXT).param("qty", ParamType.INT)
            .code(NOTHING)
            .match(json("{'use':'addToCart'}"), json("{'use':'setQuantity'}"))
            .build();
    private static final DomainStep CHECKOUT_WITH_PO = DomainStep.act("checkoutWithPo", "Check out with PO {po}")
            .param("po", ParamType.TEXT)
            .steps(json("{'action':'click','target':{" + NEW_ORDER_TAB + ",'role':'button','name':'Checkout…'}}"),
                    json("{'action':'click','target':{'window':'dialog','role':'button','name':'Next'}}"),
                    json("{'action':'click','target':{'window':'dialog','role':'button','name':'Next'}}"),
                    json("{'action':'type','target':{'window':'dialog','role':'text','name':'PO number'},'text':'{po}'}"),
                    json("{'action':'click','target':{'window':'dialog','role':'button','name':'Next'}}"))
            .build();
    private static final DomainStep PLACE_ORDER = DomainStep.act("placeOrder", "Place the order")
            .code(NOTHING)
            .match(json("{'action':'click','target':{'role':'button','name':'Place order'}}"),
                    json("{'action':'answer','button':'Place order'}"))
            .build();

    private static final List<Matcher.Candidate> ORDER_DESK = vocabulary(OPEN_NEW_ORDER, ADD_TO_CART, SET_QUANTITY,
            ADD_QTY_TO_CART, CHECKOUT_WITH_PO, PLACE_ORDER);

    @Test
    void theInterleavedPlaceOrderRecordingFoldsIntoTwoAddsAndTwoQuantities() {
        Matcher.Result r = fold(fixture("place-order"), ORDER_DESK);

        assertThat(shown(r.items)).containsExactly(
                "1", "check", "3", "4",   // the check right after File ▸ New order breaks openNewOrder
                "check", "check",
                "addToCart{product=A4 paper, 500 sheets}",
                "addToCart{product=Gel pen, black}",
                "setQuantity{product=A4 paper, 500 sheets, qty=3}",
                "setQuantity{product=Gel pen, black, qty=10}",
                "check", "check",
                "15", "check", "17", "check", "19", "check", "21", "22",   // checks between the Next clicks
                "check",
                "placeOrder{}");
        assertThat(r.notes).isEmpty();
        assertThat(r.outOfTime).isFalse();
        // each fold stands for the raw steps it took, in order
        assertThat(keys(r.folds.get(0).raw())).containsExactly(7, 8);
        assertThat(keys(r.folds.get(3).raw())).containsExactly(12);
        assertThat(r.folds.get(0).item.author).isEqualTo("person");
    }

    @Test
    void withoutTheChecksTheWholeOrderFolds() {
        List<Matcher.Item> steps = new ArrayList<Matcher.Item>();
        for (Matcher.Item i : fixture("place-order")) if (i.kind != Matcher.Item.Kind.CHECK) steps.add(i);

        Matcher.Result r = fold(steps, ORDER_DESK);

        assertThat(shown(r.items)).containsExactly(
                "openNewOrder{customer=Ada}",
                "addToCart{product=A4 paper, 500 sheets}",
                "addToCart{product=Gel pen, black}",
                "setQuantity{product=A4 paper, 500 sheets, qty=3}",
                "setQuantity{product=Gel pen, black, qty=10}",
                "checkoutWithPo{po=PO-7781}",
                "placeOrder{}");
    }

    @Test
    void addingGelPenThenItsQuantityUpgradesTheFold() {
        Matcher.Result r = fold(fixture("add-gel-pen"), ORDER_DESK);

        assertThat(shown(r.items)).containsExactly("addQtyToCart{product=Gel pen, black, qty=10}");
        // first "Add Gel pen, black to the cart", then the longer step took that fold and the quantity
        assertThat(r.folds).hasSize(2);
        assertThat(r.folds.get(0).choice.step).isEqualTo("order-desk:addToCart");
        assertThat(r.folds.get(1).span).containsExactly(r.folds.get(0).item, fixture(r, 3));
        assertThat(keys(r.folds.get(1).raw())).containsExactly(1, 2, 3);
    }

    @Test
    void anAuthorChangeBlocksAFold() {
        List<Matcher.Item> steps = fixture("add-gel-pen").subList(0, 2);
        // the person picks the row, the agent clicks Add to cart
        List<Matcher.Item> mixed = Arrays.asList(steps.get(0), as(steps.get(1), "agent", false));

        Matcher.Result r = fold(mixed, ORDER_DESK);

        assertThat(shown(r.items)).containsExactly("1", "2");
        assertThat(r.folds).isEmpty();
        // the same two steps by one author fold
        assertThat(shown(fold(steps, ORDER_DESK).items)).containsExactly("addToCart{product=Gel pen, black}");
    }

    @Test
    void aCheckBetweenTheNextClicksBreaksCheckoutWithPo() {
        List<Matcher.Item> all = fixture("place-order");
        List<Matcher.Item> checkout = new ArrayList<Matcher.Item>(all.subList(14, 22));   // Checkout… to the last Next
        assertThat(shown(checkout)).containsExactly("15", "check", "17", "check", "19", "check", "21", "22");

        assertThat(fold(checkout, ORDER_DESK).folds).isEmpty();
        checkout.remove(5);
        checkout.remove(3);
        checkout.remove(1);
        assertThat(shown(fold(checkout, ORDER_DESK).items)).containsExactly("checkoutWithPo{po=PO-7781}");
    }

    @Test
    void aTieFoldsNothingAndNamesTheCandidates() {
        DomainStep basket = DomainStep.act("addToBasket", "Put {product} in the basket").param("product", ParamType.TEXT)
                .steps(ADD_TO_CART.steps().toArray(new String[0])).build();

        Matcher.Result r = fold(fixture("add-gel-pen").subList(0, 2), vocabulary(ADD_TO_CART, basket));

        assertThat(shown(r.items)).containsExactly("1", "2");
        assertThat(r.notes).hasSize(1);
        Matcher.Note tie = r.notes.get(0);
        assertThat(tie.kind).isEqualTo(Matcher.Note.Kind.TIE);
        assertThat(tie.choices).extracting(c -> c.step).containsExactly("order-desk:addToCart", "order-desk:addToBasket");
        assertThat(keys(tie.span)).containsExactly(1, 2);
    }

    @Test
    void keepRawBlocks() {
        List<Matcher.Item> steps = fixture("add-gel-pen");

        // the Add to cart click is kept raw: nothing folds with it or across it
        Matcher.Result r = fold(Arrays.asList(steps.get(0), barrier(steps.get(1)), steps.get(2)), ORDER_DESK);

        assertThat(shown(r.items)).containsExactly("1", "2", "setQuantity{product=Gel pen, black, qty=10}");
        // a fold kept as it is does not grow into a longer one
        Matcher.Result first = fold(steps.subList(0, 2), ORDER_DESK);
        Matcher.Item kept = Matcher.Item.folded("kept", "person", first.folds.get(0).raw(), true);
        assertThat(shown(fold(Arrays.asList(kept, steps.get(2)), ORDER_DESK).items))
                .containsExactly("kept", "setQuantity{product=Gel pen, black, qty=10}");
    }

    @Test
    void theLongerRunWinsThenTheMorePreciseStep() {
        DomainStep anyButton = DomainStep.act("clickButton", "Click {label}").param("label", ParamType.TEXT)
                .code(NOTHING).match(json("{'action':'click','target':{'role':'button','name':'{label}'}}")).build();
        DomainStep addButton = DomainStep.act("clickAdd", "Click Add to cart").code(NOTHING)
                .match(json("{'action':'click','target':{'role':'button','name':'Add to cart'}}")).build();
        List<Matcher.Item> steps = fixture("add-gel-pen").subList(0, 2);

        // both one-step patterns fit the click on Add to cart, but the two-step addToCart is longer
        assertThat(shown(fold(steps, vocabulary(anyButton, addButton, ADD_TO_CART)).items))
                .containsExactly("addToCart{product=Gel pen, black}");
        // alone, the click is the more precise of the two
        assertThat(shown(fold(steps.subList(1, 2), vocabulary(anyButton, addButton)).items)).containsExactly("clickAdd{}");
    }

    @Test
    void aStepThatDoesNotFoldOnItsOwnIsSuggested() {
        DomainStep suggestOnly = DomainStep.act("addToCart", "Add {product} to the cart").param("product", ParamType.TEXT)
                .steps(ADD_TO_CART.steps().toArray(new String[0])).autoFold(false).build();

        Matcher.Result r = fold(fixture("add-gel-pen").subList(0, 2), vocabulary(suggestOnly));

        assertThat(shown(r.items)).containsExactly("1", "2");
        assertThat(r.notes).hasSize(1);
        assertThat(r.notes.get(0).kind).isEqualTo(Matcher.Note.Kind.SUGGESTED);
        assertThat(r.notes.get(0).choices.get(0).params).containsEntry("product", "Gel pen, black");
    }

    @Test
    void anUndecidedMatchFoldsNothingAndSaysWhy() {
        // three notes, each fitting alone, whose captures cannot make up the last text: undecided within the budget
        String forty = "Gel pen, black; A4 paper, 500 sheets; xy";
        DomainStep notes = DomainStep.act("notes", "Type notes").param("p1", ParamType.TEXT).param("p2", ParamType.TEXT)
                .param("p3", ParamType.TEXT).code(NOTHING)
                .match(json(note("*{p1}*")), json(note("*{p2}*")), json(note("*{p3}*")), json(note("{p1}|{p2}|{p3}")))
                .build();
        List<Matcher.Item> steps = new ArrayList<Matcher.Item>();
        for (int i = 1; i <= 3; i++) steps.add(raw(i, note(forty)));
        steps.add(raw(4, note("no|such|thing")));

        long start = System.nanoTime();
        Matcher.Result r = fold(steps, vocabulary(notes));

        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
        assertThat(r.folds).isEmpty();
        Matcher.Note undecided = r.notes.get(r.notes.size() - 1);
        assertThat(undecided.kind).isEqualTo(Matcher.Note.Kind.UNDECIDED);
        assertThat(undecided.why).isEqualTo("could not decide whether the steps are 'notes': its pattern fits them in "
                + "more ways than a match tries");
        assertThat(keys(undecided.span)).containsExactly(1, 2, 3, 4);
        // asked for by name, the run says so too
        assertThat(catchThrowableOfType(() -> Matcher.matches(steps, vocabulary(notes), later()), Pattern.TooAmbiguous.class))
                .isNotNull();
    }

    @Test
    void pastTheDeadlineNothingIsLookedAt() {
        Matcher.Result r = Matcher.fold(fixture("add-gel-pen"), ORDER_DESK, System.nanoTime() - 1);

        assertThat(r.outOfTime).isTrue();
        assertThat(r.folds).isEmpty();
        assertThat(r.notes.get(0).why).isEqualTo("folding ran out of time before it looked at this step");
        assertThat(keys(r.notes.get(0).span)).containsExactly(1);
    }

    @Test
    void aFoldByNamePastItsDeadlineSaysSo() {
        List<Matcher.Item> steps = fixture("add-gel-pen").subList(0, 2);

        Matcher.OutOfTime e = catchThrowableOfType(() -> Matcher.matches(steps, ORDER_DESK, System.nanoTime() - 1),
                Matcher.OutOfTime.class);

        assertThat(e).hasMessage("folding ran out of time before it could tell what the steps are");
    }

    @Test
    void onlyFreshStepsAreLookedAt() {
        List<Matcher.Item> steps = fixture("add-gel-pen").subList(0, 2);
        List<Matcher.Item> seen = Arrays.asList(steps.get(0), as(steps.get(1), "person", false));
        List<Matcher.Item> old = Arrays.asList(as(steps.get(0), "person", false), as(steps.get(1), "person", false));

        // a run that ends at a step looked at before is not looked at again ...
        assertThat(fold(old, ORDER_DESK).folds).isEmpty();
        // ... but an older step still takes part in a run that ends at a fresh one
        Matcher.Result r = fold(Arrays.asList(old.get(0), steps.get(1)), ORDER_DESK);
        assertThat(shown(r.items)).containsExactly("addToCart{product=Gel pen, black}");
        assertThat(fold(seen, ORDER_DESK).folds).isEmpty();
    }

    @Test
    void aStepFoldedByNameMatchesItsWholeSpan() {
        List<Matcher.Item> steps = fixture("add-gel-pen");

        assertThat(Matcher.matches(steps.subList(0, 2), ORDER_DESK, later())).extracting(c -> c.step + c.params)
                .containsExactly("order-desk:addToCart{product=Gel pen, black}");
        assertThat(Matcher.matches(steps, ORDER_DESK, later())).extracting(c -> c.step)
                .containsExactly("order-desk:addQtyToCart");
        assertThat(Matcher.matches(steps.subList(1, 3), ORDER_DESK, later())).isEmpty();
    }

    // ---- the form a recorded step is matched in

    @Test
    void aStepIsMatchedAsItsElementWasSeen() throws IOException {
        // the recorder wrote the shortest locator: the table's id and a row key; the element was seen with more
        JsonNode recorded = JSON.readTree(json("{'action':'click','target':{'id':'products','row':{'SKU':'PEN-GEL-BLK'},"
                + "'column':'SKU'}}"));
        JsonNode seen = JSON.readTree(json("{'target':{'window':'main','scope':['tab:New order · Ada Shop Ltd'],"
                + "'role':'table','row':{'SKU':'PEN-GEL-BLK','Product':'Gel pen, black'},'column':'SKU'}}"));

        JsonNode form = Matcher.matchForm(recorded, seen, false);

        assertThat(form).isEqualTo(JSON.readTree(json("{'action':'click','target':{'id':'products',"
                + "'row':{'SKU':'PEN-GEL-BLK','Product':'Gel pen, black'},'column':'SKU',"
                + "'scope':['tab:New order · Ada Shop Ltd'],'role':'table'}}")));
        assertThat(recorded.path("target").has("scope")).isFalse();   // the recorded step itself is not changed
        Pattern add = compile(ADD_TO_CART).get(0).patterns().get(0);
        JsonNode button = JSON.readTree(json("{'action':'click','target':{'scope':['tab:New order · Ada Shop Ltd'],"
                + "'role':'button','name':'Add to cart'}}"));
        assertThat(add.match(Arrays.asList(form, button)).params()).containsEntry("product", "Gel pen, black");
        assertThat(add.match(Arrays.asList(recorded, button))).isNull();
        // a step seen as nothing more is matched as recorded
        assertThat(Matcher.matchForm(recorded, null, false)).isEqualTo(recorded);
    }

    @Test
    void aRowNamedByPositionNeverMatchesAParamRow() throws IOException {
        DomainStep openRow = DomainStep.act("openRow", "Open order {n} of the list").param("n", ParamType.INT).code(NOTHING)
                .match(json("{'action':'click','target':{'role':'table','row':{'index':'{n}'}},'count':2}")).build();
        DomainStep openFirst = DomainStep.act("openFirst", "Open the first order").code(NOTHING)
                .match(json("{'action':'click','target':{'role':'table','row':{'index':0}},'count':2}")).build();
        JsonNode byPosition = JSON.readTree(json("{'action':'click','target':{'role':'table','row':{'index':0}},'count':2}"));
        JsonNode seen = JSON.readTree(json("{'target':{'role':'table','row':{'Order':'ORD-1640','Status':'Placed'}}}"));

        JsonNode form = Matcher.matchForm(byPosition, seen, true);

        // the row stays the position the person chose, not the values seen there
        assertThat(form.path("target").path("row").has("Order")).isFalse();
        assertThat(compile(openRow).get(0).patterns().get(0).match(Collections.singletonList(form))).isNull();
        assertThat(compile(openFirst).get(0).patterns().get(0).match(Collections.singletonList(form))).isNotNull();
        // named by value, the same click matches neither: its row has no index
        JsonNode byValue = Matcher.matchForm(JSON.readTree(json("{'action':'click','target':{'role':'table',"
                + "'row':{'Order':'ORD-1640'}},'count':2}")), seen, false);
        assertThat(compile(openRow).get(0).patterns().get(0).match(Collections.singletonList(byValue))).isNull();
    }

    // ---- helpers

    private static long later() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }

    private static Matcher.Result fold(List<Matcher.Item> items, List<Matcher.Candidate> vocabulary) {
        return Matcher.fold(items, vocabulary, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    private static List<Matcher.Candidate> vocabulary(DomainStep... steps) {
        List<Matcher.Candidate> out = new ArrayList<Matcher.Candidate>();
        for (PatternCompiler.Compiled c : compile(steps)) {
            assertThat(c.problems()).as(c.step().id()).isEmpty();
            assertThat(c.patterns()).as(c.step().id()).isNotEmpty();
            out.add(new Matcher.Candidate("order-desk:" + c.step().id(), c.patterns(), c.step().autoFold()));
        }
        return out;
    }

    private static List<PatternCompiler.Compiled> compile(DomainStep... steps) {
        return PatternCompiler.compile(Arrays.asList(steps), s -> PatternCompiler.Traced.none("no trace in this test"));
    }

    /** The items of fixture {@code name}: each step a fresh raw step and each check a check, the person's; key = its number. */
    private static List<Matcher.Item> fixture(String name) {
        JsonNode file;
        try (InputStream in = MatcherTest.class.getResourceAsStream("/vocab/" + name + ".json")) {
            file = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Matcher.Item> out = new ArrayList<Matcher.Item>();
        int n = 0;
        for (JsonNode s : file.path("steps")) {
            n++;
            out.add(s.has("check") ? Matcher.Item.check(n, "person") : Matcher.Item.raw(n, "person", s.get("step"), false, true));
        }
        return Collections.unmodifiableList(out);
    }

    /** The raw item with key {@code key} among the items {@code r} started from. */
    private static Matcher.Item fixture(Matcher.Result r, int key) {
        for (Matcher.Fold f : r.folds) for (Matcher.Item i : f.span) if (Integer.valueOf(key).equals(i.key)) return i;
        throw new AssertionError("no item " + key);
    }

    private static Matcher.Item raw(int key, String singleQuoted) {
        try {
            return Matcher.Item.raw(key, "person", JSON.readTree(json(singleQuoted)), false, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Matcher.Item as(Matcher.Item i, String author, boolean fresh) {
        return Matcher.Item.raw(i.key, author, i.step, i.barrier, fresh);
    }

    private static Matcher.Item barrier(Matcher.Item i) {
        return Matcher.Item.raw(i.key, i.author, i.step, true, i.fresh);
    }

    private static String note(String text) {
        return "{'action':'type','target':{'name':'Note'},'text':'" + text + "'}";
    }

    /** Each item as a line: a raw one's key, "check", a fold's step and values. */
    private static List<String> shown(List<Matcher.Item> items) {
        List<String> out = new ArrayList<String>();
        for (Matcher.Item i : items) {
            if (i.kind == Matcher.Item.Kind.CHECK) {
                out.add("check");
            } else if (i.key instanceof Matcher.Fold) {
                Matcher.Choice c = ((Matcher.Fold) i.key).choice;
                out.add(c.step.substring("order-desk:".length()) + c.params);
            } else {
                out.add(String.valueOf(i.key));
            }
        }
        return out;
    }

    private static List<Object> keys(List<Matcher.Item> items) {
        List<Object> out = new ArrayList<Object>();
        for (Matcher.Item i : items) out.add(i.key);
        return out;
    }

    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }
}
