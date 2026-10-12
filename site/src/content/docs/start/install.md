---
title: Installation
description: Start your app with the agent and check that it is ready.
sidebar:
  order: 2
---

swing-ui-agent runs inside your app's JVM as a Java agent. You add one flag to the command that starts your app. Your app's code does not change.

## Build it

It is not on Maven Central yet. Build and install it with JDK 8 and Maven 3:

```sh
git clone https://github.com/kormcrete/swing-ui-agent.git
cd swing-ui-agent
mvn -B install
```

This produces two jars in `target/`: the library, for your tests, and `swing-ui-agent-<version>-agent.jar`, the agent and command line.

## Start your app with the agent

Add `-javaagent` to the command that starts your app:

```sh
java -javaagent:swing-ui-agent-0.1.0-SNAPSHOT-agent.jar \
     -Dswingui.testsDir=/path/to/project/src/test/java \
     -jar my-app.jar
```

The agent listens on `http://127.0.0.1:8765/mcp`. `-Dswingui.testsDir` is where exported tests go. The same jar works on every supported Java; on Java 9 and newer the app needs no `--add-opens` flags. The agent's options are listed in [Agent options](/swing-ui-agent/reference/agent-options/).

## Check that it is ready

```sh
java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar session
```

Exit code `3` means no agent is reachable: the app is not running with the agent, or it listens on another port (pass `--port`). The other exit codes are in [CLI](/swing-ui-agent/reference/cli/).
