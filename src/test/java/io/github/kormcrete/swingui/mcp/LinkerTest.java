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
        RecordedStep copy = step(2, COPY, "{'fields':{'Number':'ORD-1644'},'rows':{'Orders':{'added':["
                        + "{'Order':'ORD-1643','Customer':'Ada Lovelace'},{'Order':'ORD-1644','Customer':'Ada Lovelace'}],"
                        + "'removed':[],'changed':[],'count':5}}}",
                "ORD-1644", ".fields.Number", "ORD-1643", ".rows.Orders.added[0].Order");

        Linker.Linked l = Linker.link(action("{'action':'click','count':2,'target':{'role':'table','name':'Orders',"
                + "'row':{'Order':'ORD-1644'}}}"), steps(copy));

        // the row the copy added, by the column that holds its new value (not the field that shows it too)
        assertThat(tree(l.action).at("/target/row/Order").asText()).isEqualTo("${copy.rows.Orders.added[1].Order}");
        assertThat(l.seen).containsExactly(entry("${copy.rows.Orders.added[1].Order}", "ORD-1644"));
        // typed, the same value comes from the field
        assertThat(typed("ORD-1644", steps(copy))).isEqualTo("${copy.fields.Number}");
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
    void aFieldBeatsATitle() {
        RecordedStep copy = step(1, COPY, "{'fields':{'Number':'ORD-1643'}}", "ORD-1643", ".fields.Number");
        RecordedStep close = step(2, "{'action':'click','target':{'role':'button','name':'Close'}}", "{}");
        RecordedStep open = step(3, "{'action':'click','target':{'role':'button','name':'Open'}}",
                "{'opened':{'tabs':['ORD-1643']}}", "ORD-1643", ".opened.tabs[0]");

        assertThat(typed("ORD-1643", steps(copy, close, open))).isEqualTo("${copy.fields.Number}");
        Linker.Linked tab = Linker.link(action("{'action':'click','target':{'role':'tab','name':'ORD-1643'}}"),
                steps(copy, close, open));
        assertThat(tree(tab.action).at("/target/name").asText()).isEqualTo("${copy.fields.Number}");
        // a title alone is used when nothing better made the value
        assertThat(typed("ORD-1643", steps(close, open))).isEqualTo("${open.opened.tabs[0]}");
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

    @Test
    void aStepThatHasReferencesAlreadyIsLeftAlone() {
        unchanged(type(ORDER), steps(copy()));
    }

    private static <K, V> Map.Entry<K, V> entry(K k, V v) {
        return new java.util.AbstractMap.SimpleEntry<K, V>(k, v);
    }
}
