# swing-ui-agent

[![Build](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![Tests](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Ftests.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkormcrete%2Fswing-ui-agent%2Fbadges%2Fjava.json)](https://github.com/kormcrete/swing-ui-agent/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/platform-macOS-lightgrey.svg)](#status)

Let a coding agent drive your real Swing application and write its UI tests: attach a Java agent, no changes to the app.

- **Drive:** a coding agent clicks, types and reads the live app, from a shell (`java -jar … <tool>`) or over MCP.
- **Record:** what it did becomes a JUnit 5 `@UiTest` class (`export_test`).
- **Run:** the tests run against the live app in CI, or by hand when you want to watch them work.

The tests are meant to be written by the agent, not by hand. You review them like any other code, and run them.

## Quick start

Build and install it (it is not on Maven Central yet):

```sh
mvn -B install
```

**1. Start your app with the agent.** It listens on `http://127.0.0.1:8765/mcp`:

```sh
java -javaagent:swing-ui-agent-0.1.0-SNAPSHOT-agent.jar -Dswingui.testsDir=/path/to/project/src/test/java -jar my-app.jar
```

Agent arguments go after `=`, comma-separated: `port` (default 8765), `bind` (loopback only, default `127.0.0.1`),
`mode` (`normal` or `background`) and `plugins` (see [Plugins](#plugins-optional)).
`-Dswingui.testsDir` is where exported tests go (default: `src/test/java` under the app's working directory).
The same jar works on every supported Java; on Java 9+ the app needs no `--add-opens` flags, the agent opens what it
uses.

**2. Let your coding agent drive it.** From a shell, one tool call per run:

```sh
java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar tools          # what it can do
java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar snapshot '{"depth":3,"compact":true}'
java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar click '{"target":{"role":"button","name":"Save"}}'
java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar --out orders.png screenshot
java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar export_test '{"class":"OrderSaveUiIT"}'
```

Or point an MCP client that speaks streamable HTTP at `http://127.0.0.1:8765/mcp`: same tools.

**3. Run the tests it wrote.** Add the library as a test dependency
(`io.github.kormcrete:swing-ui-agent:0.1.0-SNAPSHOT`) and run them like any JUnit 5 test while the app is up.
An exported test reads like this:

```java
@UiTest
class OrderSaveUiIT {

    @Test
    void recorded(Client ui) {
        ui.menu("Orders", "New order");
        ui.field("Customer").fill("Acme");
        ui.button("Save").click();
        assertThat(ui.table("Orders")).within(Duration.ofMillis(10000L))
                .hasCell(row("Customer", "Acme"), "Status", "Open");
    }
}
```

A test connects to the agent on `127.0.0.1:8765` (`-Dswingui.port` changes the port). Before each test, open modal
dialogs are closed. The app may still be on any screen, so every test begins by getting to its own starting screen,
through the menu bar or a shortcut. Recording works the same way: `session reset` starts it, and `export_test` warns
when a test begins anywhere else.

## Skills for your coding agent

[`skills/`](skills) holds three agent skills (`SKILL.md` folders) that teach a coding agent this workflow.
Copy the folders into your agent's skills directory (for Claude Code: `.claude/skills/` in your project):

- **swing-ui-record-test:** record a `@UiTest`: reset, reach the starting screen, act, check with `expect`, export,
  then prove it passes from another screen.
- **swing-ui-targeting:** turn "the Customer field" or "hit Save" into a locator, or report the gap.
- **swing-ui-driver:** hand UI driving to a cheaper sub-agent with scoped reads and batched steps.

## Plugins (optional)

Everything above works on any Swing app as it is. A plugin is for when you want the agent and the tests to speak
your app's own language:

- **Reset:** bring the app to a known start state before each test (close tabs, log in).
- **Login:** `@LoginAs("clerk")` and `ui.loginAs(…)`, with aliases from `.swing-ui-agent/ui-users.properties`
  (not committed; one `alias=user:password` per line).
- **Navigation:** `ui.navigate("orders")` to your app's own screens.
- **Your own functions and fixtures:** typed hooks the tests call (`ui.hooks(MyAppHooks.class)`), extra sections
  for snapshots, where exported tests go.

A plugin implements `io.github.kormcrete.swingui.spi.UiPlugin`. Load it with `plugins=my-app-plugin.jar`.

## Status

Early, `0.1.0-SNAPSHOT`. The driver runs on **macOS** with **Java 8, 11, 17, 21 and 25**: one jar, built for Java 8
so it also attaches to the legacy Swing apps still on it, and tested on every LTS release since. Linux and Windows
are in progress; until then they fail with a clear "capability gap" error rather than misbehaving.

## Documentation

A documentation site is coming at https://kormcrete.github.io/swing-ui-agent.

## Build

JDK 8 and Maven 3:

```sh
mvn -B verify
```

This produces two jars: the library, and `swing-ui-agent-<version>-agent.jar`, the shaded agent (Jackson
relocated) used for `-javaagent:` and the command line.

To run the test suite on another Java, keep building on JDK 8 and point the tests at the other JVM:
`mvn -B verify -Djvm=/path/to/jdk-21/bin/java`.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
