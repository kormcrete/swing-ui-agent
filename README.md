# Swing UI Agent

**Record UI tests for your real Swing application, and let a coding agent do the work.**

[![Build](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![Tests](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Ftests.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Fjava.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![Platform](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Fplatform.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)

Show the test in the app, or tell the agent in words. You get a recorded test, a file with nothing to compile, that
replays from the app, the agent, Maven or CI. Attach one jar to the app's JVM: no changes to the app, and it never
takes over your mouse.

![Order Desk, the demo app, with the agent asking in the app which of two tables holds the cart](site/public/hero-agent-asks.png)

**Documentation: https://kormcrete.github.io/swing-ui-agent**

**For coding agents:** the docs as plain text, [llms.txt](https://kormcrete.github.io/swing-ui-agent/llms.txt) (an index)
or [llms-full.txt](https://kormcrete.github.io/swing-ui-agent/llms-full.txt) (all of them in one file).

## How it works

- **Record:** a person shows the test with the palette in the app (● Record, Check, Done), or tells a coding agent in
  words and the agent drives the live app. Either way the agent does the work: it names each step in plain words and
  saves a **recorded test**.
- **Replay:** load it in the palette and press ▶ Run, ask the agent to run it, or run the whole `uitest/` folder from
  Maven and CI.
- **Ask:** when the agent is not sure what you mean, it boxes the candidates in the app and you confirm one.
- **Drive:** underneath, the agent clicks, types and reads the live app, from a shell (`java -jar … <tool>`) or over
  MCP.

Nobody writes the tests by hand: you read the steps, fix them in the palette, and run them.

### Recorded tests, and Java tests when you need more

- **Recorded test:** for anyone who can use the app: testers, users, and developers who want it easy. A JSON file in
  `uitest/` with nothing to compile. It holds exact values and checks what is visible, such as the cart total
  `€31.32`. Steps and checks are numbered the same in the palette, the reports and the file: step `7`, its second
  check `7.2`.
- **Data is yours:** a recorded test creates the data it checks or uses data you know exists. A value the app makes up
  (a new order number, today's date) only when the start makes it predictable.
- **Java test, when you need more:** for more or more technical checks (values that change from run to run, a
  database, files, logs, anything the test's own JVM can reach), the agent writes a JUnit 5 `@UiTest` class instead.
  The two kinds do not mix.

See [Recorded tests or Java tests](https://kormcrete.github.io/swing-ui-agent/start/choose-test-kind/).

## Quick start

It is not on Maven Central yet, so build and install it (JDK 8, Maven 3):

```sh
mvn -B install -DskipTests
```

Start your app with the agent. It listens on `127.0.0.1:8765`, and the palette appears in the app's main window:

```sh
java -javaagent:target/swing-ui-agent-0.1.0-SNAPSHOT-agent.jar -jar my-app.jar
```

Press **● Record** in the palette and show the test, or tell your coding agent what the test does. This is what the
agent runs, one call per step, to record it, save it and replay it:

```sh
UI="java -jar target/swing-ui-agent-0.1.0-SNAPSHOT-agent.jar"
$UI session '{"op":"reset"}'
$UI snapshot '{"window":"main","depth":3,"compact":true}'
$UI menu '{"path":["File","New order"]}'
# … the steps, and expect checks of what is on screen …
$UI export_recorded_test '{"name":"place-order"}'
$UI run_recorded_test '{"name":"place-order"}'
```

That writes `uitest/place-order.json` in the app's working directory. To watch it run, press **Load recorded test…**
in the palette, pick it and press **▶ Run**. When a test needs more, the agent keeps the same recording as a Java test
with `export_test '{"class":"PlaceOrderUiIT"}'`.

To see all of it, use [Order Desk](https://github.com/kormcrete/swing-order-desk-demo), a demo app made to be driven:

- **Try it:** [download it](https://kormcrete.github.io/swing-ui-agent/demo/tour/#download-and-run-it) with the agent
  built in, connect your agent and ask it to place an order.
- **Record tests into a project:** open its source in IntelliJ. Its run configurations start the app with the agent
  and run its tests. See [in IntelliJ](https://kormcrete.github.io/swing-ui-agent/start/intellij/) and
  [with Claude Code](https://kormcrete.github.io/swing-ui-agent/start/claude-code/).

## What a test looks like

Order Desk's `uitest/place-order.json`, recorded by an agent: a sentence per step, the checks under their step, exact
values only (17 steps; `// …` stands for what is left out). The palette's Steps list shows the same sentences.

```json
{
  "version" : 2,
  "name" : "place-order",
  "description" : "A new order for Ada Shop Ltd, from File ▸ New order through checkout to the placed order",
  "start" : "reset",
  "steps" : [
    {
      "action" : "menu",
      "say" : "Open a new order",
      "path" : [
        "File",
        "New order"
      ]
    },
    // …
    {
      "action" : "answer",
      "say" : "Confirm the order",
      "button" : "Place order",
      "checks" : [
        {
          "say" : "The new order is Placed",
          "target" : {
            "role" : "label",
            "name" : "Placed"
          },
          "text" : "Placed"
        },
        {
          "say" : "The receipt shows Ada Shop Ltd and €37.20",
          "target" : {
            "role" : "label",
            "name" : "Ada Shop Ltd · 12 Oct 2026 · €37.20"
          },
          "text" : "Ada Shop Ltd · 12 Oct 2026 · €37.20"
        }
      ]
    },
    // …
```

To run every file in `uitest/` from Maven and CI,
add `io.github.kormcrete:swing-ui-agent:0.1.0-SNAPSHOT` as a test dependency and one small JUnit class; see
[Run and edit a recorded test](https://kormcrete.github.io/swing-ui-agent/start/recorded-tests/#in-maven-and-ci).

**Java tests.** Where a test must read a value that changes per run, the agent writes a Java test. From Order Desk's
`PlaceOrderUiIT`:

```java
@Test
void placesAnOrderForAda(Client ui) {
    ui.menu("File", "New order");
    // … the cart, the checkout, and the new order's number, read from the status bar …
    assertThat(ui.tab("Orders").table().cell("Status")).hasCell(row("Order", order), "Status", "Placed");
}
```

## More

- [Record a test by showing it](https://kormcrete.github.io/swing-ui-agent/start/show/): the palette
  (on by default; `palette=off` turns it off) for a person at the app: ● Record, Check, Done, a sentence per step,
  Export…, and Load recorded test… to run it again, step by step, and save changes back.
- [Record together with your agent](https://kormcrete.github.io/swing-ui-agent/start/record-together/): say the test
  in chat, the agent records it, you watch in the palette and edit the same list. Ask it to "name the steps" and it
  writes a sentence per step, per check and per group, which you can change or undo.
- [Run and edit a recorded test](https://kormcrete.github.io/swing-ui-agent/start/recorded-tests/): where they live,
  the palette, the agent, Maven and CI.
- [One order, two files](https://kormcrete.github.io/swing-ui-agent/demo/two-files/): Order Desk's `place-order.json`
  and the Java test `PlaceOrderUiIT` side by side, and what each can check.
- [Skills](https://kormcrete.github.io/swing-ui-agent/agents/skills/): three agent skills in [`skills/`](skills)
  that teach your coding agent the drive, target and record workflow.
- [Plugins](https://kormcrete.github.io/swing-ui-agent/plugins/what-a-plugin-adds/) (optional): reset to a known
  state, log in, navigate and call your own hooks.
- [Plugin vocabulary](https://kormcrete.github.io/swing-ui-agent/plugins/vocabulary/) (optional): name your app's own
  steps ("Add Gel pen, black to the cart") once; the agent runs them, recordings fold into them and recorded tests
  read in your app's words.
- [Agent options](https://kormcrete.github.io/swing-ui-agent/reference/agent-options/) and the
  [tool reference](https://kormcrete.github.io/swing-ui-agent/reference/tools/).

## Platforms

Early, `0.1.0-SNAPSHOT`. One jar, built for Java 8, so it also attaches to the legacy Swing apps still on it.

- **macOS:** Java 8, 11, 17, 21 and 25, in CI.
- **Linux (X11):** Java 8 and 25, in CI under Xvfb.
- **Windows:** Java 8 and 25, in CI.

Each CI job includes an end-to-end run that starts an app with the agent jar and drives it through the command line.
Linux and Windows have been tested on CI runners and in virtual machines so far, not yet on many real desktops:
reports from yours are welcome. See [Limits](https://kormcrete.github.io/swing-ui-agent/project/limits/).

## Build

```sh
mvn -B verify
```

This builds the library and `swing-ui-agent-<version>-agent.jar`, the shaded agent used for `-javaagent:` and the
command line. To run the tests on another Java, keep building on JDK 8 and add `-Djvm=/path/to/jdk/bin/java`.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
