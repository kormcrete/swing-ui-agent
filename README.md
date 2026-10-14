# Swing UI Agent

**Let a coding agent drive your real Swing application and write its UI tests.**

[![Build](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![Tests](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Ftests.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Fjava.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![Platform](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Fplatform.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)

Attach one jar to the app's JVM: no changes to the app, and it never takes over your mouse.

![Order Desk, the demo app, with the agent asking in the app which of two tables holds the cart](site/public/hero-agent-asks.png)

**Documentation: https://kormcrete.github.io/swing-ui-agent**

## How it works

- **Drive:** a coding agent clicks, types and reads the live app, from a shell (`java -jar … <tool>`) or over MCP.
- **Record:** a test comes about in one of two ways:
  - you describe it, and the agent drives the app and exports what it did;
  - you show it with the palette in the app (● Record, Check, Done), then export it with Export… or let the agent.
- **Run:** the result is a JUnit 5 `@UiTest` class. It runs against the live app from Maven or your IDE, and in CI.
- **Ask:** when the agent is not sure what you mean, it boxes the candidates in the app and you confirm one.

Tests are recorded, not written by hand: by the agent, or by you with the palette. You review them like any other
code, and run them.

## Quick start

It is not on Maven Central yet, so build and install it (JDK 8, Maven 3):

```sh
mvn -B install -DskipTests
```

Start your app with the agent. It listens on `127.0.0.1:8765`:

```sh
java -javaagent:target/swing-ui-agent-0.1.0-SNAPSHOT-agent.jar -jar my-app.jar
```

Let your coding agent drive it, one call per step, and export the test:

```sh
UI="java -jar target/swing-ui-agent-0.1.0-SNAPSHOT-agent.jar"
$UI snapshot '{"depth":3,"compact":true}'
$UI menu '{"path":["File","New order"]}'
$UI export_test '{"class":"PlaceOrderUiIT"}'
```

The quickest way to see all of it is [Order Desk](https://github.com/kormcrete/swing-order-desk-demo), a demo app
made to be driven. Open it in IntelliJ: its run configurations start the app with the agent, run its UI tests, and
start it with the palette for recording. See
[Record and run a UI test in IntelliJ](https://kormcrete.github.io/swing-ui-agent/start/intellij/) and
[with Claude Code](https://kormcrete.github.io/swing-ui-agent/start/claude-code/).

## What a test looks like

An excerpt from Order Desk's `PlaceOrderUiIT`, recorded by an agent and then reviewed:

```java
@UiTest
class PlaceOrderUiIT {

    @Test
    void placesAnOrderForAda(Client ui) {
        ui.menu("File", "New order");
        ui.tab("New order").field("Customer").type("Ada");
        ui.press("Enter");

        assertThat(ui.tab("New order · Ada Shop Ltd")).isSelected();

        // … the cart and the checkout steps …

        ui.dialog().button("Place order").click();
        ui.answer("Place order");

        // … the new order's number, read from the status bar …

        ui.tab("Orders").click();

        assertThat(ui.tab("Orders").table().cell("Status")).hasCell(row("Order", order), "Status", "Placed");
    }
}
```

Add `io.github.kormcrete:swing-ui-agent:0.1.0-SNAPSHOT` as a test dependency and run it while the app is up.

## More

- [Skills](https://kormcrete.github.io/swing-ui-agent/agents/skills/): three agent skills in [`skills/`](skills)
  that teach your coding agent the drive, target and record workflow.
- [Record a test by showing it](https://kormcrete.github.io/swing-ui-agent/start/show/): the palette
  (on by default; `palette=off` turns it off) for a person at the app.
- [Plugins](https://kormcrete.github.io/swing-ui-agent/plugins/what-a-plugin-adds/) (optional): reset to a known
  state, log in, navigate and call your own hooks.
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
