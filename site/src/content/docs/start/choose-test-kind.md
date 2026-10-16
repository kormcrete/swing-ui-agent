---
title: Recorded tests or Java tests
description: One recording, two ways to keep it. Who each is for, what each can check, and why they do not mix.
sidebar:
  order: 2
---

A recording can be kept in two ways: as a **recorded test**, a JSON file that the agent or the palette runs, or as a **Java test**, a JUnit 5 `@UiTest` class that you compile. They look at the same app, but they are made by different people for different jobs. Pick one when you export.

## At a glance

| | Recorded test | Java test |
| --- | --- | --- |
| Who makes it | A person showing the app, often not technical: a tester, an actual user | An engineer, with the coding agent |
| What it can check | Exact values that are visible on screen | Anything: dynamic values, and technical checks such as a database, files, logs or HTTP |
| Values | Static: exactly what the person saw | Computed, parameterised, read from the app |
| Where it lives | `uitest/<name>.json` | `src/test/java/.../PlaceOrderUiIT.java` |
| How it runs | Palette (**Load recorded test…**, then **▶ Run**), the agent (`run_recorded_test`), a shell, Maven or CI | Maven, your IDE or CI, like any JUnit test |
| Needs a compiler | No | Yes |
| Review | Read the numbered sentences in the palette, or the file | Review the code like any other code |

## Who each is for

- **Recorded tests are for people who show the app.** A tester or a user presses **● Record**, uses Order Desk, adds checks of what they see, and presses **Done**. They need no Java and no IDE. The agent can also record one from your instructions in chat.
- **Java tests are for engineers.** The coding agent writes a `@UiTest` class from a recording or from a conversation, and an engineer reviews it, keeps it in the repository and extends it.
- **What a recorded test checks:** exact values that are visible, for example that the cart's **Total** reads `€31.32`. A value that changes from run to run, or a check on something the screen does not show, is a Java test.
- **Data is the user's job.** See [Data is yours](#data-is-yours).
- **They do not mix.** See [They do not mix](#they-do-not-mix).

## Why Java tests

A Java test can do more than the screen shows. After the UI steps it can check what the app left behind. This snippet is only an illustration of the idea, not a test from Order Desk:

```java
@Test
void storesTheOrder(Client ui) throws Exception {
    // … the UI steps that place the order …

    // your own connection to the database the app writes to
    try (Connection c = DriverManager.getConnection(DB_URL);
         PreparedStatement s = c.prepareStatement("select status from orders where number = ?")) {
        s.setString(1, orderNumber);
        try (ResultSet r = s.executeQuery()) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString("status")).isEqualTo("PLACED");
        }
    }
}
```

- The test runs in its own JVM, not in the app's. It drives the app from outside, as a user would.
- So it reaches what that JVM can reach: a database, files, logs, HTTP services. It never reaches the app's in-memory objects.
- That is the main reason a test is Java: wider integration tests that include technical checks.
- Order Desk keeps its orders in memory, so its Java tests check the UI only.

## Why recorded tests

- Nothing to compile or install. The file is JSON, one step per action, with its checks under it.
- It runs from the palette, from the agent (`run_recorded_test`), from a shell, and in Maven or CI with the JUnit runner `RecordedTests`.
- Steps and checks have one numbering, `7` and `7.2`, in the palette, in reports and in the file.
- It can be reviewed and fixed by the person who recorded it: replay a step, redo from a step, describe it (give it a sentence) or delete it, and save it back.

A recorded test, as the agent exports it and runs it on Order Desk. The recording began at a reset with **File ▸ New order**, so the export has no `startWarning`:

```sh
$UI export_recorded_test '{"name":"add-to-cart","description":"Starts an order for Ada Shop Ltd and adds A4 paper."}'
{"steps": 5, "checks": 1, "fragile": 0, "overwrote": false, "test": {"version": 2, "name": "add-to-cart", "start": "reset",
  "steps": [{"action": "menu", "path": ["File", "New order"]},
            {"action": "type", "target": {"role": "text", "name": "Customer"}, "text": "Ada"},
            {"action": "press", "keys": "Enter"},
            {"action": "click", "target": {"role": "table", "row": {"Product": "A4 paper, 500 sheets"}, "column": "SKU"}},
            {"action": "click", "target": {"role": "button", "name": "Add to cart"},
             "checks": [{"target": {"role": "label", "name": "Total"}, "text": "€5.88"}]}]}}

$UI run_recorded_test '{"name":"add-to-cart"}'
Recorded test 'add-to-cart' passed:
  [1] PASS menu([File, New order])
  [2] PASS type(text 'Customer' 'Ada')
  [3] PASS press(Enter)
  [4] PASS click(table row={Product=A4 paper, 500 sheets} column=SKU)
  [5] PASS click(button 'Add to cart')
    [5.1] PASS expect(label 'Total' text '€5.88')
```

Left out of the quote: the file's `description` and `savedTo` in the export, and the timings in the run report. One A4 paper makes the cart total `€5.88`; with three A4 paper and ten Gel pen it reads `€31.32`.

## Data is yours

A recorded test holds exact values, so the data behind them has to be right every time. That is your job, not the tool's.

- Check data **the test creates**, such as a customer it adds and then finds, or **data you know exists**, such as Ada Shop Ltd.
- Tests do not reuse each other's data, unless you arrange it.
- A value the app makes up, such as a new order number or today's date, can be checked **only when the start makes it predictable**. Otherwise check something else, or write a Java test.

## They do not mix

- **Export…** in the palette, and the agent's `export_test` and `export_recorded_test`, each write one kind. A recording is exported as either.
- A Java test is not run by the palette, and a recorded test is not a JUnit class. There is no step that turns one into the other.
- Using a recorded test as the base of a Java test that an engineer generalises is on the [Roadmap](/swing-ui-agent/project/roadmap/) under Later.

## What's next

- [Record a test by showing it](/swing-ui-agent/start/show/): the person records with the palette.
- [Record a test with the agent](/swing-ui-agent/start/record/): the agent drives and exports.
