package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.FlowJson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class StepWordsTest {

    private static final String LINES_IN_ORDERS = "{\"role\":\"table\",\"name\":\"Lines\",\"scope\":[\"tab:Orders\"]";

    private static JsonNode json(String text) {
        try {
            return FlowJson.mapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static Locator loc(String text) {
        try {
            return FlowJson.mapper().readValue(text, Locator.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static void said(String step, String sentence) {
        assertThat(StepWords.sentence(json(step))).isEqualTo(sentence);
    }

    @Test
    void sentencesOfEveryVerbAndExpectation() {
        said("{\"action\":\"type\",\"target\":" + LINES_IN_ORDERS + ",\"row\":{\"Number\":\"ORD-1640\"},\"column\":\"Qty\"},"
                + "\"text\":\"16\"}", "Type 16 into Qty of row ORD-1640 in Lines (tab Orders)");
        said("{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"€7.32\"}",
                "Check that Total shows €7.32");
        said("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Place order\",\"window\":\"dialog:Checkout\"}}",
                "Click Place order in dialog Checkout");
        said("{\"action\":\"click\",\"count\":2,\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Number\":\"ORD-1640\"}}}",
                "Double-click row ORD-1640 in Orders");
        said("{\"action\":\"click\",\"button\":\"right\",\"target\":{\"role\":\"table\",\"name\":\"Lines\",\"row\":{\"index\":0}}}",
                "Right-click row 1 in Lines");
        said("{\"action\":\"click\",\"modifiers\":[\"Shift\"],\"target\":{\"role\":\"list\",\"name\":\"Products\",\"item\":[\"Gel pen\"]}}",
                "Shift-click Gel pen in Products");
        said("{\"action\":\"contextMenu\",\"path\":[\"Copy product\"],\"target\":{\"role\":\"table\",\"name\":\"Lines\","
                + "\"row\":{\"Product\":\"Paper\"}}}", "Right-click row Paper in Lines and choose Copy product");
        said("{\"action\":\"menu\",\"path\":[\"Window\",\"Register\"]}", "Choose Window > Register in the menu bar");
        said("{\"action\":\"pick\",\"path\":[\"Ada Shop Ltd\"]}", "Pick Ada Shop Ltd");
        said("{\"action\":\"select\",\"target\":{\"role\":\"combo\",\"name\":\"Customer\"},\"items\":[\"Ada Shop Ltd\"]}",
                "Choose Ada Shop Ltd in Customer");
        said("{\"action\":\"select\",\"target\":{\"role\":\"combo\",\"name\":\"Customer\"},\"item\":\"Ada Shop Ltd\"}",
                "Choose Ada Shop Ltd in Customer");
        String gift = "\"target\":{\"role\":\"checkbox\",\"name\":\"Gift wrap\"}";
        said("{\"action\":\"check\"," + gift + ",\"checked\":false}", "Untick Gift wrap");
        said("{\"action\":\"check\"," + gift + ",\"checked\":true}", "Tick Gift wrap");
        said("{\"action\":\"check\"," + gift + "}", "Tick Gift wrap");
        said("{\"action\":\"press\",\"keys\":\"Mod+V\"}", "Press Mod+V");
        said("{\"action\":\"press\",\"keys\":\"Mod+V\",\"target\":{\"role\":\"text\",\"name\":\"Customer\"}}",
                "Press Mod+V in Customer");
        said("{\"action\":\"answer\",\"button\":\"Yes\"}", "Press Yes in the dialog");
        said("{\"action\":\"chooseFile\",\"path\":\"/tmp/orders.csv\"}", "Choose the file /tmp/orders.csv");
        said("{\"action\":\"chooseFile\",\"path\":\"/tmp/orders.csv\",\"approve\":false}",
                "Choose the file /tmp/orders.csv without approving");
        said("{\"action\":\"focusFileChooserName\"}", "Put the focus in the file name field");
        said("{\"action\":\"drag\",\"from\":{\"role\":\"table\",\"name\":\"Lines\",\"row\":{\"index\":0}},"
                + "\"to\":{\"role\":\"list\",\"name\":\"Cart\"}}", "Drag row 1 in Lines onto Cart");
        said("{\"action\":\"drop\",\"files\":[\"a.pdf\",\"b.pdf\"],\"target\":{\"role\":\"label\",\"name\":\"Attachments\"}}",
                "Drop a.pdf, b.pdf on Attachments");
        said("{\"action\":\"drop\",\"text\":\"ORD-1640\",\"target\":{\"role\":\"label\",\"name\":\"Attachments\"}}",
                "Drop the text ORD-1640 on Attachments");
        String qty = "\"target\":{\"role\":\"table\",\"name\":\"Lines\",\"column\":\"Qty\"}";
        said("{\"action\":\"resizeColumn\"," + qty + ",\"delta\":40}", "Widen column Qty in Lines by 40 px");
        said("{\"action\":\"resizeColumn\"," + qty + ",\"delta\":-40}", "Narrow column Qty in Lines by 40 px");
        said("{\"action\":\"resizeWindow\",\"window\":\"main\",\"width\":800,\"height\":600}",
                "Resize the main window to 800x600");
        said("{\"action\":\"closeWindow\",\"window\":\"dialog:Checkout\"}", "Close dialog Checkout");
        String place = "\"target\":{\"role\":\"button\",\"name\":\"Place order\"}";
        said("{\"action\":\"hover\",\"inside\":true," + place + "}", "Point at Place order");
        said("{\"action\":\"hover\",\"inside\":false," + place + "}", "Move the pointer off Place order");
        String orders = "\"target\":{\"role\":\"table\",\"name\":\"Orders\"}";
        said("{\"action\":\"scroll\",\"to\":\"bottom\"," + orders + "}", "Scroll Orders to the bottom");
        said("{\"action\":\"scroll\",\"pages\":2," + orders + "}", "Scroll Orders by 2 pages");
        said("{\"action\":\"scroll\"," + orders + "}", "Scroll to Orders");
        said("{\"action\":\"setClipboard\",\"text\":\"7,2\"}", "Put 7,2 on the clipboard");
        said("{\"action\":\"navigate\",\"target\":\"orders\"}", "Go to orders");
        said("{\"action\":\"close\"}", "Close the active tab");
        said("{\"action\":\"loginAs\",\"as\":\"ada\"}", "Log in as ada");
        said("{\"action\":\"relogin\"}", "Log in again");
        said("{\"action\":\"loginAs\",\"user\":\"ada.shop\"}", "Log in as ada.shop");
    }

    @Test
    void sentencesOfEveryExpectation() {
        String customer = "\"target\":{\"role\":\"text\",\"name\":\"Customer\"}";
        String total = "\"target\":{\"role\":\"label\",\"name\":\"Total\"}";
        said("{\"action\":\"expect\"," + customer + ",\"value\":\"Ada Shop Ltd\"}", "Check that Customer holds Ada Shop Ltd");
        said("{\"action\":\"expect\"," + total + ",\"tooltip\":\"\"}", "Check that Total has no tooltip");
        said("{\"action\":\"expect\"," + total + ",\"tooltip\":\"Total incl. VAT\"}",
                "Check that Total has the tooltip Total incl. VAT");
        String placeOrder = "\"target\":{\"role\":\"button\",\"name\":\"Place order\"}";
        said("{\"action\":\"expect\"," + placeOrder + ",\"state\":[\"!disabled\"]}", "Check that Place order is enabled");
        said("{\"action\":\"expect\",\"target\":{\"role\":\"checkbox\",\"name\":\"Gift wrap\"},\"state\":[\"checked\",\"!hidden\"]}",
                "Check that Gift wrap is checked and visible");
        String plain = "\"target\":{\"role\":\"table\",\"name\":\"Lines\"}";
        said("{\"action\":\"expect\"," + plain + ",\"rows\":{\"op\":\"eq\",\"value\":3}}", "Check that Lines has 3 rows");
        said("{\"action\":\"expect\"," + plain + ",\"rows\":{\"op\":\"gte\",\"value\":2}}", "Check that Lines has at least 2 rows");
        said("{\"action\":\"expect\"," + plain + ",\"rows\":{\"op\":\"lt\",\"value\":5}}", "Check that Lines has fewer than 5 rows");
        said("{\"action\":\"expect\"," + plain + ",\"cell\":{\"row\":{\"Product\":\"Paper\"},\"column\":\"Qty\",\"expected\":\"2\"}}",
                "Check that Qty of row Paper in Lines shows 2");
        said("{\"action\":\"expect\"," + plain + ",\"selected\":[0,2]}", "Check that rows 1, 3 of Lines are selected");
        said("{\"action\":\"expect\"," + plain + ",\"selected\":[]}", "Check that nothing is selected in Lines");
        String tab = "\"target\":{\"role\":\"tab\",\"name\":\"ORD-1640\"}";
        said("{\"action\":\"expect\"," + tab + ",\"absent\":true}", "Check that tab ORD-1640 is absent");
        said("{\"action\":\"expect\"," + tab + ",\"shown\":true}", "Check that tab ORD-1640 is shown");
    }

    @Test
    void sentencesOfTheRestAndOfUnknownSteps() {
        said("{\"action\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Order number\"},\"as\":\"order\"}",
                "Remember what Order number shows as order");
        said("{\"action\":\"screenshot\",\"path\":\"cart.png\",\"target\":{\"role\":\"table\",\"name\":\"Lines\"}}",
                "Take a picture of Lines");
        said("{\"action\":\"screenshot\",\"path\":\"cart.png\"}", "Take a picture of the window");
        said("{\"action\":\"do\",\"step\":\"addToCart\"}", "Do addToCart");
        said("{\"action\":\"frobnicate\"}", "Frobnicate");
        said("{}", "Step");
        said("[1,2]", "Step");
        assertThat(StepWords.sentence(null)).isEqualTo("Step");
    }

    @Test
    void placePhraseRules() {
        assertThat(StepWords.place(loc("{\"role\":\"button\"}"))).isEqualTo("the button");
        assertThat(StepWords.place(loc("{\"id\":\"clear\"}"))).isEqualTo("clear");
        assertThat(StepWords.place(loc("{\"role\":\"section\",\"name\":\"Customer\"}"))).isEqualTo("section Customer");
        assertThat(StepWords.place(loc("{\"role\":\"menuitem\",\"name\":\"Copy\"}"))).isEqualTo("menu item Copy");
        assertThat(StepWords.place(loc("{\"role\":\"button\",\"name\":\"Save~\",\"match\":\"contains\"}"))).isEqualTo("Save");
        assertThat(StepWords.place(loc("{\"role\":\"table\",\"name\":\"Lines\",\"row\":{\"index\":-1}}")))
                .isEqualTo("the last row in Lines");
        assertThat(StepWords.place(loc("{\"role\":\"table\",\"name\":\"Lines\",\"row\":{\"index\":-3}}")))
                .isEqualTo("row 3 from the end in Lines");
        assertThat(StepWords.place(loc("{\"role\":\"table\",\"name\":\"Lines\",\"column\":\"Qty\",\"row\":{\"index\":-1}}")))
                .isEqualTo("Qty of the last row in Lines");
        assertThat(StepWords.place(loc("{\"role\":\"table\",\"name\":\"Lines\",\"row\":{\"Product\":\"Paper\",\"Qty\":2}}")))
                .isEqualTo("row Paper, 2 in Lines");
        assertThat(StepWords.place(loc("{\"role\":\"text\",\"name\":\"Customer\",\"scope\":[\"section:Details\",\"text:Name\","
                + "\"id:form\"],\"window\":\"popup\",\"nth\":1,\"point\":{\"x\":0.5,\"y\":0.5}}")))
                .isEqualTo("Customer (section Details, field Name, form) in the popup (match 2)");
        assertThat(StepWords.place(loc("{\"role\":\"text\",\"name\":\"Customer\",\"window\":\"dialog\"}")))
                .isEqualTo("Customer in the dialog");
        assertThat(StepWords.place(loc("{\"role\":\"text\",\"name\":\"Customer\",\"window\":\"window:Help\"}")))
                .isEqualTo("Customer in window Help");
        assertThat(StepWords.place(loc("{\"window\":\"main\"}"))).isEqualTo("the main window");
        assertThat(StepWords.place(null)).isEqualTo("the window");
    }

    @Test
    void readsAndCheckpointsReadAsSentences() {
        Locator lines = loc("{\"role\":\"table\",\"name\":\"Lines\",\"scope\":[\"tab:Orders\"]}");
        Locator customer = loc("{\"role\":\"text\",\"name\":\"Customer\"}");
        assertThat(StepWords.read("rowCount", lines, "3")).isEqualTo("Count the rows of Lines (tab Orders): 3");
        assertThat(StepWords.read("value", customer, "Ada Shop Ltd")).isEqualTo("Read the value of Customer: Ada Shop Ltd");
        assertThat(StepWords.read("rows", lines, null)).isEqualTo("Read the rows of Lines (tab Orders)");
        assertThat(StepWords.read("clipboardText", null, "7,2")).isEqualTo("Read the clipboard: 7,2");
        assertThat(StepWords.read("text", customer, null)).isEqualTo("Read the text of Customer");
        assertThat(StepWords.read("shownText", customer, null)).isEqualTo("Read what Customer shows");
        assertThat(StepWords.read("selectedText", customer, null)).isEqualTo("Read what is selected in Customer");
        assertThat(StepWords.read("tooltip", customer, null)).isEqualTo("Read the tooltip of Customer");
        assertThat(StepWords.read("expandedItems", lines, null)).isEqualTo("Read the expanded items of Lines (tab Orders)");
        assertThat(StepWords.read("selectedRows", lines, null)).isEqualTo("Read the selected rows of Lines (tab Orders)");
        assertThat(StepWords.read("columns", lines, null)).isEqualTo("Read the columns of Lines (tab Orders)");
        assertThat(StepWords.read("count", customer, "2")).isEqualTo("Count the matches of Customer: 2");
        assertThat(StepWords.read("values", customer, null)).isEqualTo("Read the values of every Customer");
        assertThat(StepWords.read("texts", customer, null)).isEqualTo("Read the texts of every Customer");
        assertThat(StepWords.read("bounds", customer, null)).isEqualTo("Read where Customer is");
        assertThat(StepWords.read("presence", customer, "true")).isEqualTo("Look whether Customer is there: true");
        assertThat(StepWords.read("exists", customer, null)).isEqualTo("Look whether Customer is there");
        assertThat(StepWords.read("menuItems", null, "File > Export")).isEqualTo("Read the items of the menu File > Export");
        assertThat(StepWords.readMenu("File > Export", "PDF, CSV")).isEqualTo("Read the items of the menu File > Export: PDF, CSV");
        assertThat(StepWords.readMenu("File > Export", null)).isEqualTo("Read the items of the menu File > Export");
        assertThat(StepWords.read("fileChooser", null, null)).isEqualTo("Read the open file chooser");
        assertThat(StepWords.read("modalShowing", null, "true")).isEqualTo("Look whether a modal dialog shows: true");
        assertThat(StepWords.read("describeModal", null, null)).isEqualTo("Read the open dialog");
        assertThat(StepWords.read("clipboardText", null, null)).isEqualTo("Read the clipboard");

        assertThat(StepWords.checkpoint("cart-totals", lines, Collections.<Locator>emptyList()))
                .isEqualTo("Checkpoint cart-totals of Lines (tab Orders)");
        assertThat(StepWords.checkpoint("cart-totals", lines,
                Arrays.asList(loc("{\"role\":\"label\",\"name\":\"Date\"}"), loc("{\"role\":\"label\",\"name\":\"Order number\"}"))))
                .isEqualTo("Checkpoint cart-totals of Lines (tab Orders), masking Date, Order number");
        assertThat(StepWords.checkpoint("order-form", loc("{\"window\":\"main\"}"), Collections.<Locator>emptyList()))
                .isEqualTo("Checkpoint order-form of the main window");
        assertThat(StepWords.checkpoint("order-form", null, null)).isEqualTo("Checkpoint order-form of the main window");
    }

    @Test
    void aLongValueIsCutAt60Characters() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) text.append((char) ('a' + i % 26));
        String sentence = StepWords.sentence(json("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Customer\"},"
                + "\"text\":\"" + text + "\"}"));
        assertThat(StepWords.VALUE_CAP).isEqualTo(60);
        assertThat(sentence).isEqualTo("Type " + text.substring(0, 60) + "… into Customer");
    }

    @Test
    void valuesAreOneLine() {
        said("{\"action\":\"setClipboard\",\"text\":\"a\\r\\nb\\nc\"}", "Put a b c on the clipboard");
    }

    @Test
    void sentencesOfTheBranchesTheMainGoldensSkip() {
        String orders = "\"target\":{\"role\":\"table\",\"name\":\"Orders\"}";
        String lines = "\"target\":{\"role\":\"table\",\"name\":\"Lines\"}";
        said("{\"action\":\"do\",\"step\":\"addToCart\",\"say\":\"Add the gel pen to the cart\"}", "Add the gel pen to the cart");
        said("{\"action\":\"expect\"," + lines + ",\"rows\":{\"op\":\"gt\",\"value\":3}}", "Check that Lines has more than 3 rows");
        said("{\"action\":\"expect\"," + lines + ",\"rows\":{\"op\":\"lte\",\"value\":4}}", "Check that Lines has at most 4 rows");
        said("{\"action\":\"expect\"," + lines + ",\"rows\":{\"op\":\"eq\",\"value\":1}}", "Check that Lines has 1 row");
        said("{\"action\":\"click\",\"count\":3," + orders + "}", "Triple-click Orders");
        said("{\"action\":\"click\",\"modifiers\":[\"Shift\",\"Ctrl\"]," + orders + "}", "Shift+Ctrl-click Orders");
        said("{\"action\":\"click\",\"modifiers\":[\"Mod\"],\"count\":2," + orders + "}", "Mod-double-click Orders");
        said("{\"action\":\"scroll\",\"pages\":-2," + orders + "}", "Scroll Orders back by 2 pages");
        said("{\"action\":\"scroll\",\"pages\":1," + orders + "}", "Scroll Orders by 1 page");
        said("{\"action\":\"expect\"," + lines + ",\"selected\":[1]}", "Check that row 2 of Lines is selected");
        String gift = "\"target\":{\"role\":\"checkbox\",\"name\":\"Gift wrap\"}";
        said("{\"action\":\"expect\"," + gift + ",\"state\":[\"readonly\",\"!readonly\"]}",
                "Check that Gift wrap is read-only and editable");
        said("{\"action\":\"expect\"," + gift + ",\"state\":[\"!checked\",\"focused\",\"!focused\",\"hidden\",\"selected\",\"!selected\"]}",
                "Check that Gift wrap is unchecked and focused and not focused and hidden and selected and not selected");
        said("{\"action\":\"expect\"," + gift + ",\"state\":[\"disabled\",\"blinking\",\"!sparkling\"]}",
                "Check that Gift wrap is disabled and blinking and not sparkling");
        said("{\"action\":\"chooseFile\"}", "Choose a file");
        said("{\"action\":\"chooseFile\",\"approve\":false}", "Cancel the file chooser");
        said("{\"action\":\"answer\",\"button\":\"escape\"}", "Press Escape in the dialog");
        said("{\"action\":\"remember\",\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"column\":\"Number\"},"
                + "\"as\":\"order\",\"selected\":true}", "Remember what is selected in column Number in Orders as order");
        said("{\"action\":\"expect\"," + gift + ",\"text\":\"\"}", "Check that Gift wrap shows nothing");
        said("{\"action\":\"click\",\"target\":{\"role\":\"table\",\"name\":\"Lines\",\"row\":{\"index\":9999999999}}}",
                "Click row 10000000000 in Lines");
    }

    @Test
    void aMissingFieldReadsAsMissingNeverAsNullOrDoubleSpaces() {
        String customer = "\"target\":{\"role\":\"text\",\"name\":\"Customer\"}";
        said("{\"action\":\"type\"," + customer + ",\"text\":\"\"}", "Clear Customer");
        said("{\"action\":\"type\"," + customer + "}", "Type (missing) into Customer");
        said("{\"action\":\"press\"}", "Press (missing)");
        said("{\"action\":\"remember\"," + customer + "}", "Remember what Customer shows as (missing)");
        said("{\"action\":\"do\"}", "Do (missing)");
        said("{\"action\":\"navigate\"}", "Go to (missing)");
        said("{\"action\":\"answer\"}", "Press (missing) in the dialog");
        said("{\"action\":\"menu\"}", "Choose (missing) in the menu bar");
        said("{\"action\":\"pick\",\"path\":[]}", "Pick (missing)");
        said("{\"action\":\"setClipboard\"}", "Put (missing) on the clipboard");
        said("{\"action\":\"select\"," + customer + "}", "Choose (missing) in Customer");
    }

    @Test
    void malformedFieldsStillGiveASentence() {
        said("{\"action\":\"click\",\"target\":\"Place order\",\"modifiers\":\"Shift\"}", "Shift-click the window");
        said("{\"action\":\"menu\",\"path\":\"File\"}", "Choose File in the menu bar");
        said("{\"action\":\"pick\",\"path\":{\"a\":1}}", "Pick (missing)");
        said("{\"action\":{\"x\":1}}", "Step");
        said("{\"action\":\"type\",\"target\":{\"role\":{\"a\":1},\"name\":[1]},\"text\":{\"a\":1}}", "Type (missing) into the main window");
        said("{\"action\":\"expect\",\"target\":{\"role\":\"table\",\"name\":\"Lines\"},\"rows\":\"3\"}",
                "Check that Lines has (missing) rows");
        said("{\"action\":\"expect\",\"target\":{\"role\":\"table\",\"name\":\"Lines\"},\"rows\":{\"op\":\"gt\",\"value\":\"x\"}}",
                "Check that Lines has more than (missing) rows");
    }

    @Test
    void aValueOfOnlySpacesIsARealValueShownInQuotes() {
        String customer = "\"target\":{\"role\":\"text\",\"name\":\"Customer\"}";
        said("{\"action\":\"type\"," + customer + ",\"text\":\" \"}", "Type \" \" into Customer");
        said("{\"action\":\"setClipboard\",\"text\":\"   \"}", "Put \"   \" on the clipboard");
        said("{\"action\":\"expect\"," + customer + ",\"text\":\" \"}", "Check that Customer shows \" \"");
        said("{\"action\":\"press\",\"keys\":\"\\n\"}", "Press \" \"");
    }

    @Test
    void aTildeIsDroppedOnlyFromAContainsName() {
        assertThat(StepWords.place(loc("{\"role\":\"button\",\"name\":\"Backup~\"}"))).isEqualTo("Backup~");
        assertThat(StepWords.place(loc("{\"role\":\"button\",\"name\":\"Backup~\",\"match\":\"contains\"}"))).isEqualTo("Backup");
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 59; i++) text.append('a');
        text.append("😀tail");
        said("{\"action\":\"setClipboard\",\"text\":\"" + text + "\"}", "Put " + text.substring(0, 59) + "… on the clipboard");
    }
}
