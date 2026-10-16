---
title: Run the tests
description: Run the Java tests from Maven or IntelliJ against the running app, and read what a failure left behind.
sidebar:
  order: 10
---

A Java test is a plain JUnit 5 class. It does not start the app: it connects to the agent of an app that is already running and drives it the same way the agent did. This page sets up Maven the way Order Desk does, and shows what a failure leaves behind.

## 1. Add the library

Add the library as a test dependency, next to JUnit 5:

```xml
<dependency>
    <groupId>io.github.kormcrete</groupId>
    <artifactId>swing-ui-agent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

## 2. Keep UI tests out of the unit build

A `@UiTest` class is tagged `ui` and named `*UiIT`. Order Desk runs them with failsafe in a Maven profile of its own, so `mvn verify` stays unit-only and needs no app:

<!-- snippet: pom.xml#L192-L216 -->
```xml
<profile>
    <id>ui</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>2.22.2</version>
                <configuration>
                    <includes>
                        <include>**/*UiIT.java</include>
                    </includes>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

## 3. Name the agent the tests drive

A test connects to the agent on `127.0.0.1:8765` by default. Order Desk uses port 8770 and says so once, in
`src/test/resources/junit-platform.properties`, which JUnit reads however the tests run: Maven, an IntelliJ run
configuration or a single test from the gutter.

<!-- snippet: src/test/resources/junit-platform.properties#L1-L3 -->
```properties
# The swing-ui-agent the UI tests (*UiIT) drive: Order Desk started with -javaagent:...=port=8770 (the "Order Desk" run
# configuration). Read by JUnit however the tests run (Maven, IntelliJ, the gutter); override with -Dswingui.agents=...
swingui.agents=127.0.0.1:8770
```

For one run against another app, pass `-Dswingui.agents=127.0.0.1:<port>`. Without the file, `-Dswingui.port=<port>`
changes the default port.

## 4. Start the app, then run

Start the app with the agent, then run the UI tests against it:

```sh
java -javaagent:target/swing-ui-agent-agent.jar=port=8770 \
     -Dorderdesk.today=2026-10-12 -jar target/order-desk.jar &
mvn -B -Pui verify
```

To run one class: `mvn -B -Pui verify -Dit.test=PlaceOrderUiIT`.

- When no agent answers, the first test fails at once with "no swing-ui-agent on 127.0.0.1:8770", and every later test with it.
- Before each test the agent resets the app, and every test begins by getting to its own screen (see [Reset and the starting screen](/swing-ui-agent/guides/reset/)). Run a test twice against the same app and it must pass twice.
- In IntelliJ, Order Desk's run configurations start the app and run the tests: see [Record and run a UI test in IntelliJ](/swing-ui-agent/start/intellij/).
- A test can save a screenshot after each step: see [Screenshots](/swing-ui-agent/guides/screenshots/#in-a-test).

## When a test fails

A failed test writes its evidence under `target/ui-failures/<Class>/<method>/`:

- `evidence.txt`: the failure, what the locator matched, the element tree at that moment and the stack; on a timeout, also the app's event-thread stack.
- a PNG of the window at the failure.
- with several clients in one test, one `client-N` folder each.

This one failed because an earlier session had left a second "New order · Ada Shop Ltd" tab open:

```text
failure: io.github.kormcrete.swingui.junit.UiAssertionError: Expected tab 'New order · Ada Shop Ltd' to have state [selected]
but: AMBIGUOUS: 2 elements match tab 'New order · Ada Shop Ltd'; add a scope, a window or nth
waited: 10044 ms
target target: tab 'New order · Ada Shop Ltd'
  candidate: tab 'New order · Ada Shop Ltd' in main
  candidate: tab 'New order · Ada Shop Ltd' in main
tree (every window (depth 3, compact, 15 nodes)):
  window 'Order Desk' [focused]
    …
    tabs
      tab 'Orders'
      tab 'New order · Ada Shop Ltd'
      tab 'New order · Ada Shop Ltd' [selected] ...
screenshot: …/target/ui-failures/PlaceOrderUiIT/placesAnOrderForAda/failure-step0.png
```

Without a plugin, a reset does not close tabs. Restart the app, or have the test begin from a state no earlier run can disturb.

## A run report for scripts

A coding agent that runs the tests cannot watch the IDE's test window. Register the run report in your test module, in `src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener`:

```text
io.github.kormcrete.swingui.junit.UiRunReport
```

Every run that holds a UI test then writes `target/ui-runs/latest.txt`, one line at a time:

```text
STARTED 2026-10-13T10:34:55.574Z tests=1
PASS PlaceOrderUiIT#placesAnOrderForAda 4255ms
DONE passed=1 failed=0 skipped=0 aborted=0
```

## What's next

- [Run in CI](/swing-ui-agent/start/ci/).
- [Screenshots](/swing-ui-agent/guides/screenshots/) and [Leases and several clients](/swing-ui-agent/guides/leases/).
