---
title: Plugin SPI
description: The interfaces a plugin implements, how it is packaged, and how the agent loads it.
sidebar:
  order: 2
---

A plugin is a jar or a class folder with one class that implements `io.github.kormcrete.swingui.spi.UiPlugin`. It runs inside the app's JVM, next to the app's own classes. This page lists what it implements and how the agent finds and starts it. What each part buys you is on [What a plugin adds](/swing-ui-agent/plugins/what-a-plugin-adds/).

## Build against the library

Compile against the library and leave it out of the plugin jar: at run time the agent jar supplies those classes.

```xml
<dependency>
  <groupId>io.github.kormcrete</groupId>
  <artifactId>swing-ui-agent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

Add your app as a `provided` dependency too, if the plugin calls the app's classes.

## Package it

The jar or folder holds two files:

- **`META-INF/swing-ui-agent/plugin.properties`:**

  ```properties
  # required: the app class whose loading starts the plugin
  app-class=io.github.kormcrete.orderdesk.OrderDesk
  # optional: the name session reports; default: the jar's file name, or the module of target/classes
  name=order-desk
  # optional: a manifest attribute the plugin's and the app's jars must share, so a plugin
  # never runs next to another build of the app
  build-match=Implementation-Build
  ```

- **`META-INF/services/io.github.kormcrete.swingui.spi.UiPlugin`:** one line, your class's name.

A class folder works as well as a jar, which is how an IDE runs it. With `build-match`, two class folders without a manifest pass too.

## How the agent starts it

1. `plugins=order-desk-plugin.jar` in the [agent options](/swing-ui-agent/reference/agent-options/) names it. The agent reads the descriptor at startup, and `session` shows the plugin as `waiting`.
2. When the class named by `app-class` loads, the agent creates a class loader for the plugin as a child of that class's loader, so the plugin sees the app's classes.
3. `UiPlugin.start(PluginContext)` runs once, on a daemon thread whose context class loader is the plugin's. It may block until the app is ready, for example until the main window shows. The state is `starting` meanwhile.
4. What `start` returns makes the plugin `ready`. A throw marks it `failed`, with the reason in `session`; the generic tools keep working.

## UiPlugin and PluginParts

```java
public interface UiPlugin {
    PluginParts start(PluginContext context) throws Exception;
}
```

`PluginContext` gives the installed `driver()` and the plugin's `pluginName()`. `start` returns a `PluginParts`, any part of which may be null:

| Part | Type | What it serves |
| --- | --- | --- |
| `host` | `StepHost` | The app steps `navigate`, `close` and `loginAs`, live and in replay. A plugin with a host is the **app plugin**. |
| `inventory` | `UiInventory` | `describe_ui`: active tab, navigation targets, toolbar actions, menu paths, the view's tabs and sections. |
| `app` | `AppHooks` | `session` reset, login and info. |
| `hooksType`, `hooks` | an interface of yours, and its implementation | `plugin_call` and `ui.hooks(…)` in tests. |
| `export` | `ExportDefaults` | The module folder and package `export_test` writes to by default. |

The first ready plugin with a `host` is the app plugin: only its `inventory`, `app` and `export` are used. Hooks work from any plugin, so a second plugin can add hooks only.

## StepHost

```java
public interface StepHost {
    Verbs verbs();                  // the installed driver's verbs: context.driver().verbs()
    void navigate(String target);   // an entry-point id or a navigation name
    void closeActiveTab();          // answering its "close anyway?" confirm
    void loginAs(String alias);     // log out and in as an alias from ui-users.properties
    PendingDialog openDialog();     // the message or confirm waiting for an answer now, or null
}
```

App steps refuse to run while `openDialog()` reports a dialog that waits for an answer.

## AppHooks

Called on an HTTP thread, never on the event thread.

- **`reset(ResetSpec spec)`:** bring the app to one test's start state: its own dialogs closed, every tab closed, logged in as `spec`, settled. By the time it runs, the agent has already reset the pointer and closed modal dialogs with Escape. Throw, with the reason, when the app cannot get there. A dialog that will not close is a finding, not something to work around.
- **`recoverSession()`:** optional. Sign back in when the app's session expired behind a sign-in dialog. It runs before the dialogs are escaped, because Escape on such a dialog may only ask whether to exit.
- **`login(ResetSpec who, boolean force)`:** log a user in mid-test without closing anything.
- **`info()`:** the app's name and version for `session`.

`ResetSpec` is either an alias (`ResetSpec.alias("clerk")`; blank means `default`) or explicit credentials. Resolve an alias with `UiUsers.lookup(alias)`, which reads `.swing-ui-agent/ui-users.properties`.

## UiInventory

What `describe_ui` reports: `activeTabTitle()`, `navTargets()`, `entryPointIds()`, `toolbarActions()`, `menuPaths()`, `navEntryPoints()`, `viewTabs()`, `viewSections()` and `viewSectionProblems()`. Each one answers from the live app; return empty lists for what your app does not have.

## Hooks

Hooks are an interface you declare and implement. Tests call them typed; agents call them by method name.

```java
// in a module both the plugin and the tests depend on
public interface OrderDeskHooks {
    List<String> openTabTitles();
}
```

```java
// in a test
List<String> tabs = ui.hooks(OrderDeskHooks.class).openTabTitles();
```

```sh
# from an agent
java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar plugin_call '{"method":"openTabTitles"}'
```

Method names must be unique: an overloaded name is refused. Arguments and results go through JSON, so keep them to plain values, lists, maps and simple classes. With several plugins, `plugin_call` takes `"plugin":"<name>"`.

Keep hooks to what a test needs to know or prepare. A hook that clicks for the test makes the test prove nothing about that click.

## A sketch

Order Desk ships no plugin. This is the shape one would take:

```java
public final class OrderDeskPlugin implements UiPlugin {
    @Override
    public PluginParts start(PluginContext context) throws Exception {
        // wait here until the main window shows, if the app needs time to start
        StepHost host = new OrderDeskSteps(context.driver());   // your StepHost
        UiInventory inventory = new OrderDeskInventory();       // your UiInventory
        AppHooks app = new OrderDeskReset(context.driver());    // your AppHooks
        return new PluginParts(host, inventory, app,
                OrderDeskHooks.class, new OrderDeskHooksImpl(),
                new ExportDefaults(null, "io.github.kormcrete.orderdesk"));
    }
}
```

## What's next

- [What a plugin adds](/swing-ui-agent/plugins/what-a-plugin-adds/)
- [Agent options](/swing-ui-agent/reference/agent-options/): `plugins=`
- [Security](/swing-ui-agent/agents/security/): what `plugin_call` can reach.
