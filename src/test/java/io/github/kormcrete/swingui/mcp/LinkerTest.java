package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.FlowJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinkerTest {

    private static final String COPY = "{'action':'click','target':{'role':'button','name':'Copy'}}";
    /** Copy added one order; only its number is new (the customer and the status were on the screen before). */
    private static final String COPIED = "{'rows':{'Orders':{'added':[{'Order':'ORD-1643','Customer':'Ada Lovelace',"
            + "'Status':'New'}],'removed':[],'changed':[],'count':4}}}";
    private static final String ORDER = "${copy.rows.Orders.added[0].Order}";

    private static Action action(String json) {
        try {
            return FlowJson.mapper().readValue(json.replace('\'', '"'), Action.class);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static JsonNode tree(Action a) {
        return FlowJson.mapper().valueToTree(a);
    }

    /** A recorded step that changed {@code outcome}; {@code index} = value, path, value, path, ... (its value index). */
    private static RecordedStep step(int id, String action, String outcome, String... index) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int i = 0; i < index.length; i += 2) values.put(index[i], index[i + 1]);
        ObjectNode o = outcome == null ? null : (ObjectNode) FlowJson.mapper().valueToTree(read(outcome));
        return new RecordedStep(id, RecordedStep.Author.PERSON, action(action), false).withOutcome(o, values);
    }

    private static JsonNode read(String json) {
        try {
            return FlowJson.mapper().readTree(json.replace('\'', '"'));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static RecordedStep copy() {
        return step(2, COPY, COPIED, "ORD-1643", ".rows.Orders.added[0].Order");
    }

    private static List<RecordedStep> steps(RecordedStep... steps) {
        return new ArrayList<RecordedStep>(Arrays.asList(steps));
    }

    private static String type(String text) {
        return "{'action':'type','target':{'id':'search'},'text':'" + text + "'}";
    }

    /** The text the type step has after linking. */
    private static String typed(String text, List<RecordedStep> earlier) {
        return tree(Linker.link(action(type(text)), earlier).action).path("text").asText();
    }

    private static void unchanged(String json, List<RecordedStep> earlier) {
        Action a = action(json);
        Linker.Linked l = Linker.link(a, earlier);
        assertThat(tree(l.action)).as(json).isEqualTo(tree(a));
        assertThat(l.linked()).as(json).isFalse();
        assertThat(l.seen).isEmpty();
        assertThat(l.sources).isEmpty();
        assertThat(l.ids).isEmpty();
    }

    @Test
    void aNewOrderNumberTypedLaterIsLinked() {
        List<RecordedStep> earlier = steps(step(1, "{'action':'click','target':{'role':'button','name':'Orders'}}", "{}"), copy());

        Linker.Linked l = Linker.link(action(type("ORD-1643")), earlier);

        assertThat(tree(l.action).path("text").asText()).isEqualTo(ORDER);
        assertThat(l.linked()).isTrue();
        assertThat(l.seen).containsExactly(entry(ORDER, "ORD-1643"));
        assertThat(l.sources).containsExactly(2);
        assertThat(l.ids).containsExactly(entry(2, "copy"));
    }

    @Test
    void aClickOnARowTheCopyAddedLinksThatRow() {
        // the number shows in a field too, which the value index lists first
        RecordedStep copy = step(2, COPY, "{'fields':{'Number':'ORD-1644'},'rows':{'Orders':{'added':["
                        + "{'Order':'ORD-1644','Customer':'Ada Lovelace'}],'removed':[],'changed':[],'count':5}}}",
                "ORD-1644", ".fields.Number");

        Linker.Linked l = Linker.link(action("{'action':'click','count':2,'target':{'role':'table','name':'Orders',"
                + "'row':{'Order':'ORD-1644'}}}"), steps(copy));

        // the row the copy added, by the column that holds its new value (not the field that shows it too)
        assertThat(tree(l.action).at("/target/row/Order").asText()).isEqualTo("${copy.rows.Orders.added[0].Order}");
        assertThat(l.seen).containsExactly(entry("${copy.rows.Orders.added[0].Order}", "ORD-1644"));
        // typed, the same value comes from the field
        assertThat(typed("ORD-1644", steps(copy))).isEqualTo("${copy.fields.Number}");
    }

    @Test
    void aSearchThatAddedSeveralRowsIsNeverLinkedByPosition() {
        String found = "{'rows':{'Orders':{'added':[{'Order':'ORD-1643'},{'Order':'ORD-1700'},{'Order':'ORD-1701'}],"
                + "'removed':[],'changed':[],'count':3}},'items':{'Recent':{'added':['ORD-1700','ORD-1701'],'removed':[]}}}";
        RecordedStep search = step(3, "{'action':'click','target':{'role':'button','name':'Search'}}", found,
                "ORD-1643", ".rows.Orders.added[0].Order", "ORD-1700", ".rows.Orders.added[1].Order",
                "ORD-1701", ".rows.Orders.added[2].Order");

        unchanged(type("ORD-1700"), steps(search));
        unchanged("{'action':'click','target':{'role':'table','name':'Orders','row':{'Order':'ORD-1701'}}}", steps(search));
        unchanged("{'action':'select','target':{'role':'list','name':'Recent'},'item':'ORD-1700'}", steps(search));
        // once the search's outcome shrank, its index no longer offers them either
        assertThat(search.shrunk().newValues).isEmpty();
        // a value an earlier step made alone is still linked to that step
        assertThat(typed("ORD-1643", steps(copy(), search))).isEqualTo(ORDER);
        Linker.Linked click = Linker.link(action("{'action':'click','target':{'role':'table','name':'Orders',"
                + "'row':{'Order':'ORD-1643'}}}"), steps(copy(), search));
        assertThat(tree(click.action).at("/target/row/Order").asText()).isEqualTo(ORDER);
    }

    @Test
    void aValueAlreadyOnScreenBeforeTheStepIsNotLinked() {
        // the customer and the status are in the added row, but the app showed them before Copy: no new value
        List<RecordedStep> earlier = steps(copy());

        unchanged(type("Ada Lovelace"), earlier);
        unchanged("{'action':'pick','path':['Ada Lovelace']}", earlier);
        unchanged("{'action':'select','target':{'role':'combo','name':'Status'},'item':'New'}", earlier);
        unchanged("{'action':'expect','target':{'role':'label','name':'Customer'},'text':'Ada Lovelace'}", earlier);
        unchanged("{'action':'click','target':{'role':'table','name':'Orders','row':{'Customer':'Ada Lovelace'}}}", earlier);
    }

    @Test
    void shortValuesYesNoAndSmallNumbersAreNotLinked() {
        List<RecordedStep> earlier = steps(step(2, COPY, "{'fields':{'Status':'New','Paid':'Yes','Urgent':'true','Qty':'12',"
                        + "'Code':'0999','Sent':'false','Year':'2026'}}",
                "New", ".fields.Status", "Yes", ".fields.Paid", "true", ".fields.Urgent", "12", ".fields.Qty",
                "0999", ".fields.Code", "false", ".fields.Sent", "2026", ".fields.Year"));

        for (String v : new String[] {"New", "Yes", "true", "12", "0999", "false"}) unchanged(type(v), earlier);
        // four characters and not a small number: linked
        assertThat(typed("2026", earlier)).isEqualTo("${copy.fields.Year}");
    }

    @Test
    void aTypedTextContainingTheValueIsSplit() {
        assertThat(typed("Copy of ORD-1643", steps(copy()))).isEqualTo("Copy of " + ORDER);
        assertThat(typed("ORD-1643, again ORD-1643.", steps(copy()))).isEqualTo(ORDER + ", again " + ORDER + ".");
        // only typed text is split: a check compares the whole text
        unchanged("{'action':'expect','target':{'role':'label','name':'Title'},'text':'Copy of ORD-1643'}", steps(copy()));
    }

    @Test
    void anotherFormOfTheValueIsNotLinked() {
        unchanged(type("ORD 1643"), steps(copy()));
        unchanged(type("ord-1643"), steps(copy()));
        // part of a longer number or word is not the value
        unchanged(type("ORD-16430"), steps(copy()));
        unchanged(type("XORD-1643"), steps(copy()));
    }

    @Test
    void theMostRecentSourceWins() {
        RecordedStep first = step(1, "{'action':'click','id':'copy','target':{'role':'button','name':'Copy'}}", COPIED,
                "ORD-1643", ".rows.Orders.added[0].Order");
        RecordedStep delete = step(2, "{'action':'click','target':{'role':'button','name':'Delete'}}",
                "{'rows':{'Orders':{'added':[],'removed':[{'Order':'ORD-1643'}],'changed':[],'count':3}}}");
        RecordedStep again = step(3, COPY, COPIED, "ORD-1643", ".rows.Orders.added[0].Order");

        Linker.Linked l = Linker.link(action(type("ORD-1643")), steps(first, delete, again));

        assertThat(tree(l.action).path("text").asText()).isEqualTo("${copy2.rows.Orders.added[0].Order}");
        assertThat(l.sources).containsExactly(3);
        assertThat(l.ids).containsExactly(entry(3, "copy2"));
    }

    @Test
    void theMostRecentStepBeatsAFieldOfAnOlderOne() {
        RecordedStep copy = step(1, COPY, "{'fields':{'Number':'ORD-1643'}}", "ORD-1643", ".fields.Number");
        RecordedStep close = step(2, "{'action':'click','target':{'role':'button','name':'Close'}}", "{}");
        RecordedStep open = step(3, "{'action':'click','target':{'role':'button','name':'Open'}}",
                "{'texts':{'Number':'ORD-1643'}}", "ORD-1643", ".texts.Number");

        assertThat(typed("ORD-1643", steps(copy, close, open))).isEqualTo("${open.texts.Number}");
        Linker.Linked tab = Linker.link(action("{'action':'click','target':{'role':'tab','name':'ORD-1643'}}"),
                steps(copy, close, open));
        assertThat(tree(tab.action).at("/target/name").asText()).isEqualTo("${open.texts.Number}");
    }

    @Test
    void aFieldBeatsATitle() {
        // one step shows the new number in a field and as a tab title: its value index keeps the field
        @SuppressWarnings("unchecked")
        Map<String, Object> outcome = FlowJson.mapper().convertValue(
                read("{'fields':{'Number':'ORD-1643'},'opened':{'tabs':['ORD-1643']}}"), Map.class);
        Map<String, String> index = io.github.kormcrete.swingui.driver.outcome.Outcomes.newValues(
                io.github.kormcrete.swingui.driver.outcome.UiReading.builder().build(), outcome);
        RecordedStep open = new RecordedStep(1, RecordedStep.Author.PERSON,
                action("{'action':'click','target':{'role':'button','name':'Open'}}"), false)
                .withOutcome((ObjectNode) FlowJson.mapper().valueToTree(outcome), index);

        assertThat(typed("ORD-1643", steps(open))).isEqualTo("${open.fields.Number}");
    }

    @Test
    void aTwinRowWithNoNewValueStaysExact() {
        RecordedStep twin = step(2, COPY, "{'rows':{'Orders':{'added':[{'Order':'ORD-1640','Customer':'Ada Lovelace'}],"
                + "'removed':[],'changed':[],'count':4}},'message':'Order copied'}", "Order copied", ".message");

        unchanged("{'action':'click','target':{'role':'table','name':'Orders','row':{'Order':'ORD-1640'}}}", steps(twin));
    }

    @Test
    void aLiteralDollarBraceInTypedTextIsEscaped() {
        // the recording keeps a typed ${ escaped: it stays text, the value next to it is linked
        assertThat(typed("$${total} for ORD-1643", steps(copy()))).isEqualTo("$${total} for " + ORDER);
        assertThat(typed("a $${b} $$${c} ORD-1643", steps(copy()))).isEqualTo("a $${b} $$${c} " + ORDER);
        unchanged(type("$${total}"), steps(copy()));
        // a value the app made with a ${ in it matches the text as typed
        RecordedStep note = step(2, COPY, "{'fields':{'Note':'Cost ${x} each'}}", "Cost ${x} each", ".fields.Note");
        assertThat(typed("Cost $${x} each", steps(note))).isEqualTo("${copy.fields.Note}");
        // a $ right before the value cannot be written before a reference: the text stays as typed
        unchanged(type("Price $ORD-1643"), steps(copy()));
    }

    @Test
    void aRememberedValueIsLinkedByItsName() {
        RecordedStep remember = new RecordedStep(3, RecordedStep.Author.PERSON,
                action("{'action':'remember','target':{'role':'label','name':'Number'},'as':'orderNo'}"), false)
                .withOutcome(null, Collections.singletonMap("ORD-1650", ""));

        Linker.Linked l = Linker.link(action(type("ORD-1650")), steps(copy(), remember));

        assertThat(tree(l.action).path("text").asText()).isEqualTo("${orderNo}");
        assertThat(l.seen).containsExactly(entry("${orderNo}", "ORD-1650"));
        assertThat(l.sources).containsExactly(3);
        assertThat(l.ids).isEmpty();   // a remember step is named by its name
    }

    @Test
    void aValueTheStepTypedItselfIsNotASource() {
        RecordedStep customer = step(1, "{'action':'type','target':{'role':'text','name':'Customer'},'text':'Zed Example'}",
                "{'fields':{'Customer':'Zed Example'}}", "Zed Example", ".fields.Customer");
        RecordedStep pick = step(2, "{'action':'pick','path':['Grace Hopper']}",
                "{'fields':{'Customer':'Grace Hopper'}}", "Grace Hopper", ".fields.Customer");

        unchanged(type("Zed Example"), steps(customer, pick));
        unchanged(type("Grace Hopper"), steps(customer, pick));
    }

    @Test
    void aStepThatUsedALinkedValueIsNotItsSource() {
        RecordedStep delete = step(3, "{'action':'click','target':{'role':'button','name':'Delete'}}",
                "{'rows':{'Orders':{'added':[],'removed':[{'Order':'ORD-1643'}],'changed':[],'count':3}}}");
        // step 4 typed what Copy made; the field showing it again is not new work of step 4
        RecordedStep search = new RecordedStep(4, RecordedStep.Author.PERSON,
                action("{'action':'type','target':{'role':'text','name':'Number'},'text':'Copy of " + ORDER + "'}"), false)
                .withOutcome((ObjectNode) read("{'fields':{'Number':'Copy of ORD-1643','Title':'ORD-1643'}}"),
                        index("Copy of ORD-1643", ".fields.Number", "ORD-1643", ".fields.Title"))
                .withLinkSeen(Collections.singletonMap(ORDER, "ORD-1643"));
        RecordedStep copy = step(2, "{'action':'click','id':'copy','target':{'role':'button','name':'Copy'}}", COPIED,
                "ORD-1643", ".rows.Orders.added[0].Order");

        assertThat(typed("ORD-1643", steps(copy, delete, search))).isEqualTo(ORDER);
        assertThat(typed("Copy of ORD-1643", steps(copy, delete, search))).isEqualTo("Copy of " + ORDER);
    }

    private static Map<String, String> index(String... valuesAndPaths) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int i = 0; i < valuesAndPaths.length; i += 2) values.put(valuesAndPaths[i], valuesAndPaths[i + 1]);
        return values;
    }

    @Test
    void aSourceGetsAnIdFromWhatItDidUniqueAndNeverRun() {
        String made = "{'fields':{'Number':'ORD-1643'}}";
        assertThat(typed("ORD-1643", steps(step(1, "{'action':'click','target':{'role':'button','name':'Place order…'}}",
                made, "ORD-1643", ".fields.Number")))).isEqualTo("${placeOrder.fields.Number}");
        assertThat(typed("ORD-1643", steps(step(1, "{'action':'click','target':{'role':'button','name':'Run'}}",
                made, "ORD-1643", ".fields.Number")))).isEqualTo("${run2.fields.Number}");
        assertThat(typed("ORD-1643", steps(step(1, "{'action':'press','keys':'ctrl N'}", made, "ORD-1643", ".fields.Number"))))
                .isEqualTo("${press.fields.Number}");
        assertThat(typed("ORD-1643", steps(step(1, "{'action':'menu','path':['Order','New order']}", made,
                "ORD-1643", ".fields.Number")))).isEqualTo("${newOrder.fields.Number}");
        // taken by an earlier step's id or a remembered name
        RecordedStep named = step(1, "{'action':'click','id':'copy','target':{'role':'button','name':'Copy'}}", "{}");
        RecordedStep remembered = new RecordedStep(2, RecordedStep.Author.PERSON,
                action("{'action':'remember','target':{'role':'label','name':'Number'},'as':'copy2'}"), false);
        assertThat(typed("ORD-1643", steps(named, remembered, step(3, COPY, made, "ORD-1643", ".fields.Number"))))
                .isEqualTo("${copy3.fields.Number}");
    }

    @Test
    void stepIdsGivenInOneLinkAreUniqueToo() {
        RecordedStep a = step(1, COPY, "{'fields':{'Number':'ORD-1643'}}", "ORD-1643", ".fields.Number");
        RecordedStep b = step(2, COPY, "{'fields':{'Number':'ORD-1644'}}", "ORD-1644", ".fields.Number");

        Linker.Linked l = Linker.link(action(type("ORD-1643 and ORD-1644")), steps(a, b));

        // named in the order of the steps
        assertThat(tree(l.action).path("text").asText()).isEqualTo("${copy.fields.Number} and ${copy2.fields.Number}");
        assertThat(l.ids).containsOnly(entry(1, "copy"), entry(2, "copy2"));
        assertThat(l.sources).containsOnly(1, 2);
    }

    @Test
    void valuesInPicksSelectionsChecksRowsItemsTabsAndDomainStepsAreLinked() {
        List<RecordedStep> earlier = steps(copy());
        String r = ORDER;

        assertThat(tree(Linker.link(action("{'action':'pick','path':['ORD-1643']}"), earlier).action).at("/path/0").asText())
                .isEqualTo(r);
        assertThat(tree(Linker.link(action("{'action':'select','target':{'role':'list','name':'Recent'},'item':'ORD-1643'}"),
                earlier).action).path("item").asText()).isEqualTo(r);
        assertThat(tree(Linker.link(action("{'action':'select','target':{'role':'list','name':'Recent'},"
                + "'items':['ORD-1640','ORD-1643']}"), earlier).action).path("items").toString())
                .isEqualTo("[\"ORD-1640\",\"" + r + "\"]");
        JsonNode check = tree(Linker.link(action("{'action':'expect','target':{'role':'label','name':'Number'},"
                + "'text':'ORD-1643'}"), earlier).action);
        assertThat(check.path("text").asText()).isEqualTo(r);
        JsonNode value = tree(Linker.link(action("{'action':'expect','target':{'role':'text','name':'Number'},"
                + "'value':'ORD-1643'}"), earlier).action);
        assertThat(value.path("value").asText()).isEqualTo(r);
        JsonNode cell = tree(Linker.link(action("{'action':'expect','target':{'role':'table','name':'Lines'},"
                + "'cell':{'row':{'Ref':'ORD-1643'},'column':'Ref','expected':'ORD-1643'}}"), earlier).action);
        assertThat(cell.at("/cell/row/Ref").asText()).isEqualTo(r);
        assertThat(cell.at("/cell/expected").asText()).isEqualTo(r);
        assertThat(tree(Linker.link(action("{'action':'click','target':{'role':'tree','name':'Orders',"
                + "'item':['Open','ORD-1643']}}"), earlier).action).at("/target/item/1").asText()).isEqualTo(r);
        assertThat(tree(Linker.link(action("{'action':'click','target':{'role':'tab','name':'ORD-1643'}}"), earlier).action)
                .at("/target/name").asText()).isEqualTo(r);
        assertThat(tree(Linker.link(action("{'action':'do','step':'openOrder','params':{'number':'ORD-1643'}}"), earlier)
                .action).at("/params/number").asText()).isEqualTo(r);
    }

    @Test
    void namesThatAreCaptionsAndNotValuesAreNotLinked() {
        // a window titled Orders opened; the table and the field named Orders keep their names
        List<RecordedStep> earlier = steps(step(1, "{'action':'click','target':{'role':'button','name':'Show all'}}",
                "{'opened':{'windows':['Orders']}}", "Orders", ".opened.windows[0]"));

        unchanged("{'action':'click','target':{'role':'table','name':'Orders','row':{'index':2}}}", earlier);
        unchanged("{'action':'type','target':{'role':'text','name':'Orders'},'text':'x'}", earlier);
        unchanged("{'action':'click','target':{'window':'Orders','role':'button','name':'Save'}}", earlier);
        unchanged("{'action':'menu','path':['Window','Orders']}", earlier);
        unchanged("{'action':'click','say':'Orders','target':{'role':'button','name':'Save'}}", earlier);
    }

    @Test
    void aUniquePerRunTextIsLinkedToTheSameComposedValue() {
        RecordedStep note = new RecordedStep(1, RecordedStep.Author.PERSON,
                action("{'action':'type','target':{'role':'text','name':'Note'},'text':'Note ${run.id}'}"), false)
                .withLinkSeen(Collections.singletonMap("${run.id}", "k3j9x2ab"));

        assertThat(typed("Note k3j9x2ab", steps(note))).isEqualTo("Note ${run.id}");
        assertThat(typed("Re: Note k3j9x2ab", steps(note))).isEqualTo("Re: Note ${run.id}");
        Linker.Linked row = Linker.link(action("{'action':'click','target':{'role':'table','name':'Orders',"
                + "'row':{'Note':'Note k3j9x2ab'}}}"), steps(note));
        assertThat(tree(row.action).at("/target/row/Note").asText()).isEqualTo("Note ${run.id}");
        assertThat(row.seen).containsExactly(entry("${run.id}", "k3j9x2ab"));
        assertThat(row.ids).isEmpty();
    }

    // ---- the person's links ------------------------------------------------------------------------------------------

    @Test
    void theOffersAreWhatEarlierStepsChangedThatTheStepHasNewestFirst() {
        RecordedStep copy = step(2, COPY, "{'rows':{'Orders':{'added':[{'Order':'ORD-1643','Customer':'Ada Lovelace'}],"
                + "'removed':[],'changed':[],'count':4}}}", "ORD-1643", ".rows.Orders.added[0].Order",
                "Ada Lovelace", ".rows.Orders.added[0].Customer");
        RecordedStep open = step(3, "{'action':'click','target':{'role':'button','name':'Open'}}",
                "{'fields':{'Number':'ORD-1643','Qty':'12'}}", "ORD-1643", ".fields.Number", "12", ".fields.Qty");
        Linker.Pool pool = new Linker.Pool();
        pool.add(copy);
        pool.add(open);

        List<Linker.Offer> offers = pool.fitting(action(type("Copy of ORD-1643, 12")), 10);
        // the newest step first; a row is one offer with its new values; short values too (the person chooses)
        assertThat(offers).extracting(o -> o.source.id + " " + o.group + " " + o.values.keySet()).containsExactly(
                "3 .fields.Number [ORD-1643]", "3 .fields.Qty [12]", "2 .rows.Orders.added[0] [ORD-1643, Ada Lovelace]");
        // what is not in the step is not offered; nor what a reference holds already
        assertThat(pool.fitting(action(type("ORD-1644")), 10)).isEmpty();
        assertThat(pool.fitting(action(type(ORDER)), 10)).isEmpty();
        assertThat(pool.fitting(action(type("ORD-1643")), 1)).hasSize(1);
    }

    @Test
    void aLinkByHandUsesTheChosenStepAndTakesShortValues() {
        RecordedStep copy = copy();
        RecordedStep open = step(3, "{'action':'click','target':{'role':'button','name':'Open'}}",
                "{'fields':{'Number':'ORD-1643','Qty':'12'}}", "ORD-1643", ".fields.Number", "12", ".fields.Qty");

        // the automatic link takes the newest; the person may take the older one
        assertThat(typed("ORD-1643", steps(copy, open))).isEqualTo("${open.fields.Number}");
        Linker.Linked older = Linker.linkTo(action(type("ORD-1643")), steps(copy, open), 2, ".rows.Orders.added[0]");
        assertThat(tree(older.action).path("text").asText()).isEqualTo(ORDER);
        assertThat(older.ids).containsExactly(entry(2, "copy"));
        // a short value the automatic links leave alone
        Linker.Linked qty = Linker.linkTo(action(type("12 boxes")), steps(copy, open), 3, ".fields.Qty");
        assertThat(tree(qty.action).path("text").asText()).isEqualTo("${open.fields.Qty} boxes");
        assertThat(qty.seen).containsExactly(entry("${open.fields.Qty}", "12"));
        // a value the step does not have: nothing
        assertThat(Linker.linkTo(action(type("ORD-1644")), steps(copy, open), 2, ".rows.Orders.added[0]").linked()).isFalse();
        assertThat(Linker.linkTo(action(type("ORD-1643")), steps(copy, open), 9, ".fields.Number").linked()).isFalse();
    }

    @Test
    void aTextMadeUniquePerRunIsLinkedInItsPlainFormBeforeItRuns() {
        RecordedStep customer = new RecordedStep(1, RecordedStep.Author.PERSON,
                action("{'action':'type','target':{'id':'customer'},'text':'Al ${run.id}'}"), false);

        Linker.Linked l = Linker.link(action(type("Orders of Al")), steps(customer));
        assertThat(tree(l.action).path("text").asText()).isEqualTo("Orders of Al ${run.id}");
        // linked, though no run used the id yet
        assertThat(l.linked()).isTrue();
        assertThat(l.seen).isEmpty();
        assertThat(typed("Alice", steps(customer))).isEqualTo("Alice");
        // a run reference the person did not add this way gives no plain form
        RecordedStep dated = new RecordedStep(1, RecordedStep.Author.PERSON,
                action("{'action':'type','target':{'id':'note'},'text':'Order ${run.date}'}"), false);
        assertThat(typed("Order", steps(dated))).isEqualTo("Order");

        Linker.Linked back = Linker.uniqueBack(action("{'action':'expect','target':{'id':'status'},'text':'Al'}"), customer,
                "Al", "Al ${run.id}");
        assertThat(tree(back.action).path("text").asText()).isEqualTo("Al ${run.id}");
        assertThat(Linker.uniqueForm("Al ${run.id}")).isEqualTo("end");
        assertThat(Linker.uniqueForm("${run.id} Al")).isEqualTo("start");
        assertThat(Linker.uniqueForm("${run.id}")).isNull();
        assertThat(Linker.uniqueBase("${run.id} Al")).isEqualTo("Al");
    }

    @Test
    void aCheckIsNeverLinkedToWhatItsOwnElementShows() {
        // Save changed the status, a field and a cell: a check on one of them compared with itself would check nothing
        RecordedStep save = step(2, "{'action':'click','target':{'name':'save'}}", "{'texts':{'status':'saved Ada Shop'},"
                        + "'fields':{'Billing \u203a Customer':'Ada Shop Ltd'},'rows':{'Lines':{'added':[],'removed':[],"
                        + "'changed':[{'before':{'Ref':'20'},'after':{'Ref':'ORD-1643'}}],'count':1}}}",
                "saved Ada Shop", ".texts.status", "Ada Shop Ltd", ".fields['Billing \u203a Customer']",
                "ORD-1643", ".rows.Lines.changed[0].after.Ref");
        List<RecordedStep> earlier = steps(save);

        unchanged("{'action':'expect','target':{'name':'status'},'text':'saved Ada Shop'}", earlier);
        unchanged("{'action':'expect','target':{'id':'status'},'text':'saved Ada Shop'}", earlier);
        unchanged("{'action':'expect','target':{'scope':['tab:Billing'],'role':'text','name':'Customer'},"
                + "'value':'Ada Shop Ltd'}", earlier);
        unchanged("{'action':'expect','target':{'role':'table','name':'Lines'},'cell':{'row':{'index':0},'column':'Ref',"
                + "'expected':'ORD-1643'}}", earlier);
        // another element showing the same value is checked against it
        assertThat(tree(Linker.link(action("{'action':'expect','target':{'role':'label','name':'Title'},"
                + "'text':'saved Ada Shop'}"), earlier).action).path("text").asText()).isEqualTo("${save.texts.status}");
        assertThat(tree(Linker.link(action("{'action':'expect','target':{'role':'table','name':'Lines'},'cell':{'row':"
                + "{'index':0},'column':'Code','expected':'ORD-1643'}}"), earlier).action).at("/cell/expected").asText())
                .isEqualTo("${save.rows.Lines.changed[0].after.Ref}");
    }

    @Test
    void aCheckInAnotherScopeIsAnotherElement() {
        RecordedStep save = step(2, "{'action':'click','target':{'name':'save'}}",
                "{'fields':{'Billing \u203a Name':'Ada Shop Ltd'}}", "Ada Shop Ltd", ".fields['Billing \u203a Name']");
        String billing = "${save.fields['Billing \u203a Name']}";

        // the whole key, scope and all: Shipping's Name is checked against Billing's
        assertThat(tree(Linker.link(action("{'action':'expect','target':{'scope':['tab:Shipping'],'role':'text',"
                + "'name':'Name'},'value':'Ada Shop Ltd'}"), steps(save)).action).path("value").asText()).isEqualTo(billing);
        // a locator that names no scope may be either one: the key does not say it is its own
        assertThat(tree(Linker.link(action("{'action':'expect','target':{'role':'text','name':'Name'},"
                + "'value':'Ada Shop Ltd'}"), steps(save)).action).path("value").asText()).isEqualTo(billing);
        unchanged("{'action':'expect','target':{'scope':['tab:Billing'],'role':'text','name':'Name'},'value':'Ada Shop Ltd'}",
                steps(save));
        // the window's title in the key too
        RecordedStep dialog = step(2, "{'action':'click','target':{'name':'save'}}",
                "{'fields':{'Address \u203a Billing \u203a Name':'Ada Shop Ltd'}}", "Ada Shop Ltd",
                ".fields['Address \u203a Billing \u203a Name']");
        unchanged("{'action':'expect','target':{'window':'dialog:Address','scope':['tab:Billing'],'role':'text',"
                + "'name':'Name'},'value':'Ada Shop Ltd'}", steps(dialog));
    }

    @Test
    void aCheckOfARowAStepAddedIsLinkedAndAnotherSourceStandsInForItsOwnValue() {
        // the added row is new on every run: the check of its number in its own table is linked by it
        JsonNode cell = tree(Linker.link(action("{'action':'expect','target':{'role':'table','name':'Orders'},"
                + "'cell':{'row':{'Order':'ORD-1643'},'column':'Order','expected':'ORD-1643'}}"), steps(copy())).action);
        assertThat(cell.at("/cell/row/Order").asText()).isEqualTo(ORDER);
        assertThat(cell.at("/cell/expected").asText()).isEqualTo(ORDER);
        // the field's own value (the newest source) is skipped: the row the copy added before it is used
        RecordedStep open = step(3, "{'action':'click','target':{'role':'button','name':'Open'}}",
                "{'fields':{'Number':'ORD-1643'}}", "ORD-1643", ".fields.Number");
        assertThat(tree(Linker.link(action("{'action':'expect','target':{'role':'text','name':'Number'},"
                + "'value':'ORD-1643'}"), steps(copy(), open)).action).path("value").asText()).isEqualTo(ORDER);
    }

    @Test
    void aTextMadeUniqueIsTheTypingStepsOwnValueNotTheFieldItFilled() {
        // the field Note shows what step 1 typed, as it was before it was made unique
        RecordedStep note = step(1, "{'action':'type','target':{'role':'text','name':'Note'},'text':'Copied note ${run.id}'}",
                "{'fields':{'Note':'Copied note'}}", "Copied note", ".fields.Note");

        Linker.Linked l = Linker.link(action("{'action':'expect','target':{'role':'table','name':'Register'},"
                + "'cell':{'row':{'Number':'REG-1003'},'column':'Note','expected':'Copied note'}}"), steps(note));

        assertThat(tree(l.action).at("/cell/expected").asText()).isEqualTo("Copied note ${run.id}");
        assertThat(l.ids).isEmpty();
    }

    @Test
    void aTreeItemAStepAddedIsLinkedByItsWholePath() {
        RecordedStep folder = step(2, "{'action':'click','target':{'role':'button','name':'New folder'}}",
                "{'items':{'Folders':{'added':['Folders > Folder 3'],'removed':[]}}}",
                "Folders > Folder 3", ".items.Folders.added[0]");
        String click = "{'action':'click','target':{'role':'tree','name':'Folders','item':['Folders','Folder 3']}}";

        Linker.Linked l = Linker.link(action(click), steps(folder));

        assertThat(tree(l.action).at("/target/item").toString()).isEqualTo("[\"${newFolder.items.Folders.added[0]}\"]");
        assertThat(l.seen).containsExactly(entry("${newFolder.items.Folders.added[0]}", "Folders > Folder 3"));
        // offered by hand too
        Linker.Pool pool = new Linker.Pool();
        pool.add(folder);
        assertThat(pool.fitting(action(click), 10)).hasSize(1);
        // another path stays as it is
        unchanged("{'action':'click','target':{'role':'tree','name':'Folders','item':['Folders','Folder 1']}}", steps(folder));
        // the same path added to another tree is not this tree's
        RecordedStep archived = step(2, "{'action':'click','target':{'role':'button','name':'Archive folder'}}",
                "{'items':{'Archive':{'added':['Folders > Folder 3'],'removed':[]}}}",
                "Folders > Folder 3", ".items.Archive.added[0]");
        assertThat(tree(Linker.link(action(click), steps(archived)).action).at("/target/item").toString())
                .isEqualTo("[\"Folders\",\"Folder 3\"]");
    }

    @Test
    void aValueRememberedAfterTheRowItCameFromWins() {
        RecordedStep remembered = step(3, "{'action':'remember','target':{'role':'table','name':'Orders','column':'Order'},"
                + "'as':'orderNumber','selected':true}", null, "ORD-1643", "");

        Linker.Linked l = Linker.link(action("{'action':'expect','target':{'role':'table','name':'Orders'},"
                + "'cell':{'row':{'Order':'ORD-1643'},'column':'Status','expected':'New'}}"), steps(copy(), remembered));

        // the most recent source: the person named the number, so the row is found by that name
        assertThat(tree(l.action).at("/cell/row/Order").asText()).isEqualTo("${orderNumber}");
        // remembered before the copy: the row the copy added
        RecordedStep before = step(1, "{'action':'remember','target':{'role':'label','name':'Last'},'as':'last'}", null,
                "ORD-1643", "");
        assertThat(tree(Linker.link(action("{'action':'click','target':{'role':'table','name':'Orders',"
                + "'row':{'Order':'ORD-1643'}}}"), steps(before, copy())).action).at("/target/row/Order").asText())
                .isEqualTo(ORDER);
    }

    @Test
    void aFreshValueInATabScopeAndTheNameIsLinkedInBoth() {
        Linker.Linked l = Linker.link(action("{'action':'expect','target':{'scope':['tab:ORD-1643'],'role':'label',"
                + "'name':'ORD-1643'},'shown':true}"), steps(copy()));
        assertThat(tree(l.action).at("/target/scope/0").asText()).isEqualTo("tab:" + ORDER);
        assertThat(tree(l.action).at("/target/name").asText()).isEqualTo(ORDER);
        assertThat(l.seen).containsExactly(entry(ORDER, "ORD-1643"));
    }

    @Test
    void aWindowTitleThatIsTheFreshValueIsLinkedInALocatorAndInCloseWindow() {
        assertThat(tree(Linker.link(action("{'action':'click','target':{'window':'window:ORD-1643','role':'button',"
                + "'name':'Save'}}"), steps(copy())).action).at("/target/window").asText()).isEqualTo("window:" + ORDER);
        assertThat(tree(Linker.link(action("{'action':'closeWindow','window':'dialog:ORD-1643'}"), steps(copy())).action)
                .path("window").asText()).isEqualTo("dialog:" + ORDER);
    }

    @Test
    void aGlobTitleIsNeverLinked() {
        unchanged("{'action':'click','target':{'window':'dialog:ORD-1643*','scope':['tab:ORD-1643*'],'role':'button',"
                + "'name':'Save'}}", steps(copy()));
        unchanged("{'action':'click','target':{'window':'window:*ORD-1643','role':'button','name':'Save'}}", steps(copy()));
    }

    @Test
    void aValueOnlyInsideATitleIsNotLinked() {
        unchanged("{'action':'click','target':{'scope':['tab:Order ORD-1643'],'role':'button','name':'Save'}}", steps(copy()));
    }

    @Test
    void idAndTextScopeStepsAndColumnsStayExactAndSaySo() {
        Linker.Linked l = Linker.link(action("{'action':'click','target':{'scope':['id:ORD-1643'],'role':'button',"
                + "'name':'Save'}}"), steps(copy()));
        assertThat(l.linked()).isFalse();
        assertThat(l.notLinked).containsExactly("scope step 'id:ORD-1643'");
        assertThat(Linker.link(action("{'action':'click','target':{'role':'table','name':'Orders','column':'ORD-1643'}}"),
                steps(copy())).notLinked).containsExactly("column 'ORD-1643'");
    }

    @Test
    void aTitleAStepOpenedIsNeverLinkedByItself() {
        RecordedStep open = step(3, "{'action':'click','target':{'role':'button','name':'Open'}}",
                "{'opened':{'tabs':['ORD-1643','Items','Invoice'],'dialogs':['Copy order']}}",
                "ORD-1643", ".opened.tabs[0]", "Invoice", ".opened.tabs[2]", "Copy order", ".opened.dialogs[0]");
        unchanged("{'action':'click','target':{'role':'tab','name':'Invoice'}}", steps(open));
        unchanged("{'action':'click','target':{'role':'tab','name':'ORD-1643'}}", steps(open));
        unchanged("{'action':'click','target':{'scope':['tab:Invoice'],'role':'button','name':'Print'}}", steps(open));
        unchanged("{'action':'click','target':{'window':'dialog:Copy order','role':'button','name':'OK'}}", steps(open));
        // still offered by hand
        Linker.Pool pool = new Linker.Pool();
        pool.add(open);
        assertThat(pool.fitting(action("{'action':'click','target':{'role':'tab','name':'Invoice'}}"), 5)).hasSize(1);
        assertThat(tree(Linker.linkTo(action("{'action':'click','target':{'role':'tab','name':'Invoice'}}"), steps(open), 3,
                ".opened.tabs[2]").action).at("/target/name").asText()).isEqualTo("${open.opened.tabs[2]}");
    }

    @Test
    void aValueIsNeverLinkedThroughAPathThatNamesIt() {
        RecordedStep shown = step(3, "{'action':'click','target':{'role':'button','name':'Open'}}",
                "{'texts':{'ORD-1643':'ORD-1643'}}", "ORD-1643", ".texts['ORD-1643']");
        unchanged(type("ORD-1643"), steps(shown));
        Linker.Pool pool = new Linker.Pool();
        pool.add(shown);
        assertThat(pool.fitting(action(type("ORD-1643")), 5)).isEmpty();
        assertThat(Linker.namesItself(".texts['ORD-1643']", "ORD-1643")).isTrue();
        assertThat(Linker.namesItself(".rows.Orders.added[0].Number", "ORD-1643")).isFalse();
        // the older row source is taken instead
        assertThat(typed("ORD-1643", steps(copy(), shown))).isEqualTo(ORDER);
    }

    @Test
    void aPathNamesItsValueOnlyWhenAPartOfAKeyIsThatValueWhole() {
        assertThat(Linker.namesItself(".fields['ORD-1643 › Number']", "ORD-1643")).isTrue();
        assertThat(Linker.namesItself(".texts['ORD-1643 #2']", "ORD-1643")).isTrue();
        assertThat(Linker.namesItself(".texts['Billing › ORD-1643 #2']", "ORD-1643")).isTrue();
        // a caption that holds the value inside it, a scope that is a number, a fixed key of an outcome, a twin's number
        assertThat(Linker.namesItself(".rows['Open orders'].added[0].Number", "Open")).isFalse();
        assertThat(Linker.namesItself(".fields['Line 1 › Qty']", "1")).isFalse();
        assertThat(Linker.namesItself(".rows.Orders.added[0].Number", "added")).isFalse();
        assertThat(Linker.namesItself(".texts.Status", "texts")).isFalse();
        assertThat(Linker.namesItself(".fields['Qty #2']", "2")).isFalse();
    }

    @Test
    void relinkPutsBackALinkWhereTheEditedStepHasTheSameValue() {
        RecordedStep old = new RecordedStep(5, RecordedStep.Author.AGENT, action("{'action':'click','target':{'role':'table',"
                + "'name':'Orders','row':{'Number':'" + ORDER + "'}}}"), false)
                .withLinkSeen(Collections.singletonMap(ORDER, "ORD-1643"));
        Linker.Linked same = Linker.relink(action("{'action':'click','target':{'role':'table','name':'Orders',"
                + "'row':{'Number':'ORD-1643'},'column':'Number'}}"), old);
        assertThat(tree(same.action).at("/target/row/Number").asText()).isEqualTo(ORDER);
        assertThat(same.seen).containsExactly(entry(ORDER, "ORD-1643"));
        assertThat(Linker.relink(action("{'action':'click','target':{'role':'table','name':'Orders','row':{'Number':'ORD-1640'}}}"),
                old).linked()).isFalse();
        // whole inside typed text too, as a link goes
        RecordedStep typedOld = new RecordedStep(6, RecordedStep.Author.AGENT, action(type(ORDER)), false)
                .withLinkSeen(Collections.singletonMap(ORDER, "ORD-1643"));
        assertThat(tree(Linker.relink(action(type("Copy of ORD-1643")), typedOld).action).path("text").asText())
                .isEqualTo("Copy of " + ORDER);
        // a value seen through a text with more than one reference in it is not one link: not put back
        RecordedStep two = new RecordedStep(7, RecordedStep.Author.AGENT, action(type(ORDER + " " + ORDER)), false)
                .withLinkSeen(Collections.singletonMap(ORDER + " x", "ORD-1643"));
        assertThat(Linker.relink(action(type("ORD-1643")), two).linked()).isFalse();
    }

    @Test
    void valueAtReadsAPlaceAndATitle() {
        Action a = action("{'action':'expect','target':{'window':'window:ORD-1641','scope':['tab:ORD-1641'],'role':'label',"
                + "'name':'ORD-1641'},'shown':true}");
        assertThat(Linker.valueAt(a, "target.name")).isEqualTo("ORD-1641");
        assertThat(Linker.valueAt(a, "target.scope[0]")).isEqualTo("ORD-1641");
        assertThat(Linker.valueAt(a, "target.window")).isEqualTo("ORD-1641");
        assertThat(Linker.valueAt(a, "target.role")).isEqualTo("label");   // a text, though no link can go there
        assertThat(Linker.valueAt(a, "text")).isNull();
        assertThat(Linker.valueAt(a, "target")).isNull();
        assertThat(Linker.valueAt(action(type("Copy of ORD$${1641")), "text")).isEqualTo("Copy of ORD${1641");
        assertThat(Linker.valueAt(action(type(ORDER)), "text")).isNull();
    }

    @Test
    void aTitleThatHoldsAReferenceGivesNoScopedKey() {
        assertThat(Linker.ownKeys(read("{'scope':['tab:${copy.x}'],'role':'label','name':'Total'}"))).containsExactly("Total");
    }

    @Test
    void aStepThatHasReferencesAlreadyIsLeftAlone() {
        unchanged(type(ORDER), steps(copy()));
    }

    private static <K, V> Map.Entry<K, V> entry(K k, V v) {
        return new java.util.AbstractMap.SimpleEntry<K, V>(k, v);
    }
}
