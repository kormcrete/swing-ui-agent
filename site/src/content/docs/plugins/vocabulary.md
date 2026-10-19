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

Order Desk ships a plugin of its own, in the standalone Maven project `order-desk-plugin/` beside the app. It drives the app through the agent's `Client` only and names the app class as a string in `plugin.properties`, so it needs no app classes. It offers a reset (`OrderDeskApp`: every order tab closed, the Orders list shown) and a vocabulary:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskPlugin.java#L17-L22 -->
```java
@Override
public PluginParts start(PluginContext context) {
    Client live = new Client(context.driver().verbs(), new ReadsOnly(), "order-desk-plugin");
    OrderDeskApp app = new OrderDeskApp(context.driver().verbs());
    return new PluginParts(app, null, app, null, null, null, new OrderDeskVocabulary(live));
}
```

Its steps: `openNewOrder`, `addToCart`, `setQuantity`, `addQtyToCart`, `checkoutWithPo`, `placeOrder`, `openOrder` and the check `cartTotalIs`.

## Write a step

A step is built with `DomainStep.act(id, sentence)` for something that changes the app, or `DomainStep.check(id, sentence)` for something that checks it. The sentence has `{param}` placeholders. Its body is Java code that works through the same `Client` a UI test uses:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskVocabulary.java#L78-L85 -->
```java
DomainStep.act("addToCart", "Add {product} to the cart")
        .about("Adds one of a product to the cart of the open new order")
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
| `steps` | Instead of `code`: the body as step JSON with `{param}` placeholders, as a recorded test holds it. |
| `match`, `matchAlso` | The clicks a recording must contain to fold into the step (see below). |
| `java` | The typed call a Java test makes for the step. A recorded test can hold any step; only an export as a Java test needs this. |
| `autoFold(false)` | A recording only suggests the step instead of folding into it. |

A step's values are concrete. Nothing is captured or kept between steps, and a recorded test holds no placeholders. A value the app makes up, such as a new order's number, is read by a helper method that returns it (`String placeOrder(Client ui)` in the steps class) for Java tests; the body returns nothing.

### Change one place

Each name the app shows is written once, in the plugin's steps class. The steps' code uses it, and so do the patterns that recognise a person's clicks:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskSteps.java#L32-L37 -->
```java
/** A new order's tab title until its customer is chosen; then "New order · Ada Shop Ltd". */
static final String NEW_ORDER_TAB = "New order";
static final String CUSTOMER_FIELD = "Customer";
static final String PRODUCT_COLUMN = "Product";
/** Only the products table has it: the cart table lists a product too once it is in the cart. */
static final String SKU_COLUMN = "SKU";
```

The body and the typed call both live in that class, so a Java test calls what the recorded test runs:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskSteps.java#L67-L72 -->
```java
/** Adds one of {@code product} to the cart of the open new order: selects the product, then Add to cart. */
public static void addToCart(Client ui, String product) {
    // the SKU cell: the cart table lists the product too once it is in the cart, but has no SKU column
    newOrder(ui).table().row(PRODUCT_COLUMN, product).cell(SKU_COLUMN).click();
    newOrder(ui).button(ADD_TO_CART_BUTTON).click();
}
```

When the app renames the "Add to cart" button, change its constant: the step, the pattern and every test that uses it follow.

## What a recording folds

A step's **pattern** is the run of recorded steps it stands for. Without `match`, the agent derives it from the body: when the plugin becomes ready it runs the body once or twice on a Client that reaches no app and notes the steps it would make. With `match`, you write it. `matchAlso` adds another way to do the same thing:

<!-- snippet: order-desk-plugin/src/main/java/io/github/kormcrete/orderdesk/uitest/OrderDeskVocabulary.java#L93-L100 -->
```java
DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
        .about("Adds a product to the cart of the open new order and sets its quantity")
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
- `{"use":"<id>"}` stands for another step's own pattern, so `addQtyToCart` is `addToCart` then `setQuantity`, for the same product.

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
- `do_action` and `batch` run a `do` step. The step fails like any other: `passed` says so.
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
