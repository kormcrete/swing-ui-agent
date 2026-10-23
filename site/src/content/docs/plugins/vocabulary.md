---
title: Plugin vocabulary
description: "Name your app's own steps once, such as Add Gel pen, black to the cart, and the agent, the recorder and your Java tests all use them."
sidebar:
  order: 2
---

A vocabulary is a list of **domain steps** that a plugin offers: "Open a new order for Ada Shop Ltd", "Add 10 × Gel pen, black to the cart", "The cart total is €31.32". Each one stands for the clicks and checks it takes. It is optional, and it needs no change to the app.

- **The agent runs a step in one call**, with concrete values, instead of a dozen clicks.
- **The recorder recognises them.** When a person shows the steps (or the agent runs the clicks), the recording folds them into the domain step and the palette shows one row with the sentence.
- **The recorded test reads like the user's language.** A step is a `do` step that holds the step's name and its values, and it replays through the same code.
- **A Java test can call the same step** as a typed method, when a test needs more than a recorded test can say.

Everything on this page is for the person who writes the plugin. The people who record and run tests only see the sentences. The vocabulary sits next to the other plugin parts on [What a plugin adds](/swing-ui-agent/plugins/what-a-plugin-adds/); the interfaces are on the [Plugin SPI](/swing-ui-agent/plugins/spi/) page.

## Order Desk's vocabulary

Order Desk ships a plugin of its own, in the standalone Maven project `order-desk-plugin/` beside the app. It works through the agent's driver and plain Swing, and names the app class as a string in `plugin.properties`, so it needs no app classes. It offers a reset (`OrderDeskApp`: every order tab closed, the Orders list shown), what `describe_ui` reports (`OrderDeskInventory`) and a vocabulary:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskPlugin.java#L18-L24 -->
```java
@Override
public PluginParts start(PluginContext context) {
    Client live = new Client(context.driver().verbs(), new ReadsOnly(), "order-desk-plugin");
    OrderDeskApp app = new OrderDeskApp(context.driver().verbs());
    OrderDeskInventory inventory = new OrderDeskInventory(context.driver().windows()::main);
    return new PluginParts(app, inventory, app, null, null, null, new OrderDeskVocabulary(live));
}
```

Its 98 steps cover every screen of the app, in six groups:

- **Starting an order:** `openNewOrder`, `chooseCustomer`, `typeCustomer`, `openNewOrderFromRecent`, the customer checks `newOrderIsFor`, `customerSuggestionsAre`, `customerTermsAre` and `recentCustomersAre`.
- **Products and cart:** `addToCart`, `setQuantity`, `addQtyToCart`, `addProductsToCart`, `selectProducts`, `removeFromCart`, `keepCartLines`, `browseCategory`, `searchProducts`, `showInCategory`, and the checks `cartTotalIs`, `cartLinesAre`, `cartLineIs`, `cartAmountIs`, `productsListedAre`, `productIs`, `lowStockWarningIs`, `buttonIsDisabled` and more.
- **Checkout:** `startCheckout`, `checkoutWithPo`, `setCheckoutField`, `chooseShipping`, `payByInvoice`, `payByCard`, `checkoutNext`, `checkoutBack`, `editReviewSection`, `answerCreditLimit`, `placeOrder`, `cancelCheckout`, and the checks `checkoutStepIs`, `checkoutFieldIs`, `checkoutFieldErrorIs`, `shippingPriceIs`, `reviewLineIs`, `reviewAmountIs`, `placeOrderConfirmationIs` and more.
- **An order's tab:** `openOrder`, `saveReceipt`, `keepExistingReceipt`, `cancelOrder`, `keepOrder`, and the checks `orderStatusIs`, `orderFactsAre`, `orderItemsAre`, `orderItemIs`, `orderDeliveryIs`, `orderInvoiceIs`, `suggestedReceiptFileIs`, `receiptSaveErrorIs`.
- **The Orders list:** `searchOrders`, `clearOrderSearch`, `filterOrders`, `sortOrders`, `selectOrders`, `cancelOrders`, `keepOrders`, `copyOrderNumbers`, `showOrders`, and the checks `orderIs`, `ordersListedAre`, `ordersCountIs`, `ordersEmptyMessageIs`, `selectedOrdersAre`, `cancelQuestionIs`.
- **The main window:** `selectTab`, `closeTab`, `keepTab`, `switchTheme`, `cancelExit`, and the checks `openTabsAre`, `selectedTabIs`, `menuItemIs`, `statusLineIs`, `openOrdersCountIs`, `exitQuestionIs`, `aboutShows`.

An agent wrote them from the app's source, with the [agent builds the plugin](/swing-ui-agent/plugins/agent-builds-the-plugin/) workflow; the plugin's `COVERAGE.md` lists every action and value of the app and the step that covers it.

## Write a step

A step is built with `DomainStep.act(id, sentence)` for something that changes the app, or `DomainStep.check(id, sentence)` for something that checks it. The sentence has `{param}` placeholders. Its body is Java code that works through the same `Client` a UI test uses:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskVocabulary.java#L107-L117 -->
```java
DomainStep.act("addToCart", "Add {product} to the cart")
        .about("Needs: a new order showing, the product listed (see Browse category, Search the products)."
                + " Ends: on it, one more in the product's cart line. {product}: the name or its start"
                + " (\"stapler\"); one that fits several or none fails naming them; a product Add to cart"
                + " refuses (out of stock) fails with the app's reason")
        .param("product", ParamType.TEXT).choices(this::productNames)
        .code((ui, p) -> OrderDeskSteps.addToCart(ui, p.text("product")))
        .match(json("{'action':'click','target':{%s}}", PRODUCT_ROW), ADD_BUTTON)
        .matchAlso(json("{'action':'contextMenu','target':{%s},'path':[%s]}", PRODUCT_ROW, text(ADD_TO_CART_ITEM)))
        .java(STEPS, "addToCart(ui, {product})")
        .build(),
```

| Part | What it does |
| --- | --- |
| `act` / `check` | The step's id and its sentence. A check never changes the app. |
| `about` | One line that tells the agent when to use the step. |
| `param` | A value the step takes. `ParamType.TEXT` or `ParamType.INT`; `optional(default)` lets a call leave it out. |
| `choices` | A supplier of the values that fit right now, for example the product names the app lists. The agent sees them when it looks the step up. They help it pick; a call may pass others. They are asked on a thread of their own and given up after 5 seconds. |
| `code` | The Java body. Runs on the agent's request thread, not on the event thread. |
| `codeWithOutcome` | Instead of `code`: a Java body that also returns what the step made, for later steps (see [Values a step makes](#values-a-step-makes)). |
| `steps` | Instead of `code`: the body as step JSON with `{param}` placeholders, as a recorded test holds it. |
| `match`, `matchAlso` | The clicks a recording must contain to fold into the step (see below). |
| `java` | The typed call a Java test makes for the step. A recorded test can hold any step; only an export as a Java test needs this. |
| `autoFold(false)` | A recording only suggests the step instead of folding into it. |

A call gives the step concrete values. While recording, a value an earlier step made is linked in the params like a typed value; a check's params are never linked to what the check reads. A later step can still use what the step made, as it can after any action; see below.

### Values a step makes

A `do` step changes the screen like any action, so a later step of a recorded test can use what it changed: the row it added, the tab it opened (see [Values from earlier steps](/swing-ui-agent/guides/values-from-earlier-steps/)). A value the screen does not show whole, such as a number the body reads out of a longer text, can be returned by the body itself. Use `codeWithOutcome` in place of `code`:

```java
DomainStep.act("placeOrder", "Place the order")
        .about("Places the open order and returns its number")
        .codeWithOutcome((ui, p) -> Collections.<String, Object>singletonMap("number", OrderDeskSteps.placeOrder(ui)))
        .java(STEPS, "placeOrder(ui)")
        .build(),
```

- The body returns JSON-like values: text, numbers, booleans, and lists and maps of them, or null for none. Anything else fails the step in words.
- The values go under `step` in what the step changed. A later step uses them as `${placeOrder.step.number}`, and the recorder links them automatically like a field's value. An agent gets them with `"outcome": true`.
- A value found only by its position in a list the body returned (`${showOrders.step.orders[3].Customer}`, or `added[0]` of a list of names) is never linked, not even by hand: the next run's list may hold it elsewhere. Return what a later step should use by name, or in a map keyed by a stable name, such as the order number: `${showOrders.step.orders.ORD-1634.Customer}`. When the same value sits in both, the keyed path is the one linked.
- The runs that learn a step's pattern or its Java form run the body too, and leave its values out.
- **In a Java export** a `do` step whose Java form is a typed call (`java(...)`) or several steps cannot be the source of a later step's value, and its `step` values have no Java form: `outcomeOf` takes exactly one action. The export names the problem; the recorded test is not affected. A Java test takes the value from what the helper returns: `String number = OrderDeskSteps.placeOrder(ui);`.

(Order Desk's own `placeOrder` uses `code`: its recorded tests check the order without its number.)

### Change one place

Each name the app shows is written once, in the plugin's steps class. The steps' code uses it, and so do the patterns that recognise a person's clicks:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskSteps.java#L35-L40 -->
```java
/** A new order's tab title until its customer is chosen; then "New order · Ada Shop Ltd". */
static final String NEW_ORDER_TAB = "New order";
static final String CUSTOMER_FIELD = "Customer";
static final String PRODUCT_COLUMN = "Product";
/** Only the products table has it: the cart table lists a product too once it is in the cart. */
static final String SKU_COLUMN = "SKU";
```

The typed call lives in that class too (it hands the work to the class for the new order's screen), so a Java test calls what the recorded test runs:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskSteps.java#L274-L277 -->
```java
/** Adds one of {@code product} (its name or the start of it) to the cart of the open new order. */
public static void addToCart(Client ui, String product) {
    OrderDeskNewOrderSteps.addToCart(ui, product);
}
```

When the app renames the "Add to cart" button, change its constant: the step, the pattern and every test that uses it follow.

## What a recording folds

A step's **pattern** is the run of recorded steps it stands for. Without `match`, the agent derives it from the body: when the plugin becomes ready it runs the body once or twice on a Client that reaches no app and notes the steps it would make. With `match`, you write it. `matchAlso` adds another way to do the same thing:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskVocabulary.java#L129-L137 -->
```java
DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
        .about("Needs: a new order showing, the product listed. Ends: on it, the product's cart line at"
                + " that quantity (above stock the app keeps it and the cart warns)")
        .param("product", ParamType.TEXT).choices(this::productNames)
        .param("qty", ParamType.INT)
        .code((ui, p) -> OrderDeskSteps.addQtyToCart(ui, p.text("product"), p.integer("qty")))
        .match(json("{'use':'addToCart'}"), json("{'use':'setQuantity'}"))
        .java(STEPS, "addQtyToCart(ui, {product}, {qty})")
        .build(),
```

In the text of a written pattern:

- `{name}` of a declared param captures the value (at least one character; digits for an INT param);
- `*` matches any text;
- a backslash makes a following `*`, opening brace or backslash plain text;
- fields left out match anything; an entry with `"optional":true` may be missing;
- `{"use":"<id>"}` stands for another step's own pattern, so `addQtyToCart` is `addToCart` then `setQuantity`, for the same product;
- `window`, `timeout`, `nth`, `fragile` and `via` are not compared. A written entry that names a `window` still folds, in any window, and `session` lists it under `vocabularyProblems` with the step kept, so a pattern cannot yet limit a fold to a dialog.

A recorded step is matched as its element was seen, not as the shorter locator the recorder wrote: its full scope, role, name, and a table row with every cell of it. That holds for a cell check's `cell.row` too, so a pattern such as `'cell':{'row':{'Product':'{product}'},'column':'Price','expected':'{price}'}` takes the product of the row the person checked, whichever column the recorder keyed the row by. A row the person named by its position stays that position.

A tab or section title ending in `*` matches a title prefix (`tab:New order*`, for "New order · Ada Shop Ltd") only as a **scope step** of a target, never as the leaf target itself.

### When a fold happens

A run of one author's steps folds as soon as it matches one step, whoever made the steps. These stop a fold: a check, a `do` step, a change of author, a group with a sentence, a raw step with its own sentence, a step you told to stay raw, and the start of a replay or a redo. A tie between two steps, or a step that only suggests, folds nothing; the recording lists why.

- A **check** step (`DomainStep.check`) never folds by itself. It shows in the palette's **Check** menu when the element's checks fit it, filled from the screen ("The cart total is €31.32"), and is recorded as a `do` check.
- **Unfold** puts a fold's raw steps back and marks them kept raw, so they do not fold again. `keepRaw` does the same for steps that were never folded.
- A fold is one edit: **Undo** takes it back.

In the palette's **Steps** a domain step is one row with a diamond (◆) and its sentence, with its values in bold. A fold has a ▸ that shows the steps it stands for. The bar says "recognised …" with an Undo for a few seconds after a fold, and under the list a hint tells which step the last clicks could become.

## The agent

```sh
UI="java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar --port 8770"
$UI vocabulary '{"op":"list"}'
$UI vocabulary '{"op":"describe","step":"addToCart"}'
$UI do_action '{"action":{"action":"do","step":"addToCart","params":{"product":"Gel pen, black"}}}'
$UI batch '{"steps":[{"verb":"do","step":"addToCart","params":{"product":"Gel pen, black"}}]}'
```

- `list` gives every step with its sentence, `about`, params and a one-line summary of what it folds. `describe` gives one step in full, with the values that fit right now. `match {from, to}` is a dry run of what a range of the recording would fold into.
- `do_action` and `batch` run a `do` step. The step fails like any other: `passed` says so. With `"continueOnFailedCheck":true` on the batch, a failed check step (and a failed `expect`) is reported and the batch goes on; a failed act step still stops it.
- A `do` step that fails part way leaves the app where it stopped. On a recording, the steps of it that ran are recorded as plain steps, so the recording matches the screen: the result lists them (`recordedPart`) and says so in words (`recordedPartNote`); for a tool error, the error's details end with the same words. A step that cannot be recorded raw (it holds a secret, or has no Java form) ends what is kept, and the words say which one: `recording redo {"from":n}` records from there again. When not even its first step can be kept, nothing is recorded (`recordedPart` is empty) and the words say that the recording does not match the screen, and which `recording redo` puts the app back where the recording ends. The kept steps stay raw: they do not fold back into the step that failed (`fold` them by hand if you want). A failed check records nothing. Finish the rest by hand, or `redo` from the kept steps.
- Try a step on the record at hand before you trust it in a `batch`: a step may not fit every record, such as an optional field with another caption.
- On a recording: `fold {from, to}` folds steps into the step they match, `unfold {n}` puts them back and `keepRaw {from, to}` keeps them raw. The list shows where a run did not fold and why. See the [tool reference](/swing-ui-agent/reference/tools/).

## In a recorded test

A `do` step stores the step's name and its concrete values. The name carries its plugin (`order-desk:addToCart`), even when there is only one plugin, and the file says which plugins it `requires` (format version 3):

<!-- snippet: uitest/cart-with-steps.json#L30-L38 -->
```json
{
  "action" : "do",
  "say" : "Add 3 × A4 paper, 500 sheets to the cart",
  "step" : "order-desk:addQtyToCart",
  "params" : {
    "product" : "A4 paper, 500 sheets",
    "qty" : "3"
  }
},
```

The whole file is `uitest/cart-with-steps.json` in the Order Desk repository. Format details are in the [recorded test format](/swing-ui-agent/reference/recorded-test-format/).

A recorded test that begins with a `do` step counts as starting at a defined screen when the steps behind it open with a menu, a key press or `navigate`; one that opens with a click still gets the start warning.

## In a Java test

A Java test calls the typed method. Order Desk's `CartTotalsUiIT` is the same cart as the recorded test:

<!-- snippet: src/test/java/io/github/kormcrete/orderdesk/CartTotalsUiIT.java#L22-L32 -->
```java
void totalsTheCart(Client ui) {
    openNewOrder(ui, "Ada");

    assertThat(ui.tab("New order · Ada Shop Ltd")).isSelected();

    addQtyToCart(ui, "A4 paper, 500 sheets", 3);
    addQtyToCart(ui, "Gel pen, black", 10);

    assertThat(ui.tab("New order · Ada Shop Ltd").table().cell("Line total"))
            .hasCell(row("Product", "A4 paper, 500 sheets"), "Line total", "€14.70");
    cartTotalIs(ui, "€31.32");
```

The app's own project depends on the plugin project with test scope. In IntelliJ, after you edit `OrderDeskSteps`, install the plugin (`mvn -f order-desk-plugin/pom.xml install`) or import its `pom.xml` as a second Maven project before you run a test from the gutter; otherwise the test uses the last installed jar.

## Reset, login and navigation

A plugin with only a vocabulary adds no reset: the agent's own reset stays (pointer home, modal dialogs closed). `loginAs`, `navigate` and `close` still need an app plugin with a step host (see the [Plugin SPI](/swing-ui-agent/plugins/spi/)). Reset and starting screens: [Reset and the starting screen](/swing-ui-agent/guides/reset/).

## Things to know

- **A body without `match` runs at ready.** To learn its pattern the agent runs the body twice, with stand-in values (the first TEXT value is the param's name in braces, the second `" Ab1, c "`; an INT is a large number, then 1), each time on a thread of its own with a 5 second limit. The Client it gets reaches no app, but anything else the body does (plugin code, files, a database) really happens, twice. Keep a body to its `Client` calls, or give the step a `match`. A step without a `java(...)` call also runs twice whenever such a `do` step is recorded, listed or loaded (once per step and values), to learn the steps it makes for the Java export.
- **A check needs `match` and `java`.** A check's Java body reads the app, which the trace cannot answer, so it yields no pattern and no steps. Without `match(...)` it never folds and never shows in the Check menu; without `java(...)` a Java export refuses it. Order Desk's `cartTotalIs` gives both. A check with declarative `steps(...)` needs neither.
- **Set `name=` in `plugin.properties`.** Recorded tests store `<name>:<step>`, so a plugin with a vocabulary must name itself: without `name=` the agent does not load the vocabulary, and `session` says why in `vocabularyProblems` (a name taken from the jar or folder would change with it). A second plugin with the same name is refused.
- **Secrets.** A body that reads a secret should use `Client.secret(alias, name)`. Plugin code that calls `UiUsers.secret` itself puts the secret into the steps the trace notes, and so into the pattern.
- **Who runs it.** A `do` step runs plugin code inside the app, as `plugin_call` does. See [Security](/swing-ui-agent/agents/security/).
- **Bad steps are dropped, not fatal.** A step with an id, a param, a pattern or a typed call that does not check out is left out when the plugin becomes ready, and `session` lists why. The plugin stays ready.

## Run it with the app

```sh
mvn -f order-desk-plugin/pom.xml install
java -javaagent:target/swing-ui-agent-agent.jar=port=8770,plugins=order-desk-plugin/target/classes -jar target/order-desk.jar
```

## What's next

- [Plugin SPI](/swing-ui-agent/plugins/spi/): the interfaces, packaging and loading.
- [Recorded tests](/swing-ui-agent/start/recorded-tests/): run and edit them.
- [Record together with your agent](/swing-ui-agent/start/record-together/)
