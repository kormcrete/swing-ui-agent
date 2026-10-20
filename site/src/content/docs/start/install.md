---
title: Installation
description: Build swing-ui-agent, start an app with the agent, and check that it is ready.
sidebar:
  order: 3
---

swing-ui-agent runs inside your app's JVM as a Java agent. You add one flag to the command that starts your app, and the app's code does not change. This page starts Order Desk, the demo app, with the agent; the same steps work for your own app.

## 1. Build and install it

It is not on Maven Central yet. Build and install it with JDK 8 and Maven 3:

```sh
git clone https://github.com/kormcrete/swing-ui-agent.git
(cd swing-ui-agent && mvn -B install -DskipTests)
```

This installs two jars into your local Maven repository and leaves them in `swing-ui-agent/target/`:

- `swing-ui-agent-0.1.0-SNAPSHOT.jar`: the library, for your tests.
- `swing-ui-agent-0.1.0-SNAPSHOT-agent.jar`: the Java agent and the command line client.

## 2. Build Order Desk

```sh
git clone https://github.com/kormcrete/swing-order-desk-demo.git
cd swing-order-desk-demo
mvn -B -f order-desk-plugin/pom.xml install
mvn -B package
```

The first `mvn` installs Order Desk's plugin (its vocabulary of the app's own steps), which the app's tests use. The
second writes `target/order-desk.jar`, the app, and copies the agent jar to `target/swing-ui-agent-agent.jar`.

## 3. Start the app with the agent

Add `-javaagent` to the command that starts the app:

```sh
java -javaagent:target/swing-ui-agent-agent.jar=port=8770,plugins=order-desk-plugin/target/classes \
     -Dorderdesk.today=2026-10-12 \
     -jar target/order-desk.jar
```

- `port=8770` is the port Order Desk's run configurations and tests use. Without it the agent listens on 8765, at
  `http://127.0.0.1:8765/mcp`.
- `plugins=order-desk-plugin/target/classes` loads Order Desk's plugin. The recorded test `cart-with-steps` uses its
  steps and fails without it.
- `-Dorderdesk.today` is Order Desk's own: it fixes the date, so every run looks the same.
- For your own app, add `-Dswingui.testsDir=/path/to/project/src/test/java`: that is where exported tests go. The default is `src/test/java` under the app's working directory.

The agent's options (`port`, `bind`, `mode`, `palette`, `plugins`) are in [Agent options](/swing-ui-agent/reference/agent-options/).

## 4. Check that it is ready

```sh
java -jar target/swing-ui-agent-agent.jar --port 8770 session
```

The answer names the agent, the port and the plugin state (trimmed). Its `app` part also says when the app started
and which folders are on its classpath (folders only, not jars):

```json
{"agent":{"version":"0.1.0-SNAPSHOT",…},"endpoint":{"bind":"127.0.0.1","port":8770},"mode":"normal","palette":true,…,"lease":null,"plugins":[],"plugin":{"state":"none","detail":"no plugin configured (agent argument plugins=…)"}}
```

## The agent jar in your own project

Copy the agent jar to one fixed path in your own build, so the `-javaagent` flag, run configurations and shell
commands all name the same file. Order Desk does it in its `pom.xml`:

<!-- snippet: pom.xml#L169-L193 -->
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <version>3.8.1</version>
    <executions>
        <execution>
            <!-- target/swing-ui-agent-agent.jar, for java -javaagent:... -->
            <id>agent-jar</id>
            <phase>package</phase>
            <goals><goal>copy</goal></goals>
            <configuration>
                <artifactItems>
                    <artifactItem>
                        <groupId>io.github.kormcrete</groupId>
                        <artifactId>swing-ui-agent</artifactId>
                        <version>${swing-ui-agent.version}</version>
                        <classifier>agent</classifier>
                        <outputDirectory>${project.build.directory}</outputDirectory>
                        <destFileName>swing-ui-agent-agent.jar</destFileName>
                    </artifactItem>
                </artifactItems>
            </configuration>
        </execution>
    </executions>
</plugin>
```

The copy is made at `package`. After you reinstall swing-ui-agent, build your project again to pick up the new jar.

## When it does not connect

- **Exit code `3`:** no agent is reachable. The app is not running with the agent, or it listens on another port: pass `--port <n>` before the tool name.
- **Exit code `1`:** the agent answered with an error; the message is on stderr.
- **Exit code `2`:** the command line itself is wrong.

The exit codes and flags are in [CLI](/swing-ui-agent/reference/cli/).

## What's next

- [Let your agent drive the app](/swing-ui-agent/start/drive/).
- [Limits](/swing-ui-agent/project/limits/): platforms and Java versions.
