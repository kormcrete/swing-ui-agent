---
title: Plugin SPI
description: The interfaces a plugin implements, how it is packaged, and how the agent loads it.
sidebar:
  order: 3
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
  # the name session reports and recorded tests store (<name>:<step>); required when the plugin
  # has a vocabulary, else optional (default: the jar's file name, or the module of target/classes)
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
| `vocabulary` | `Vocabulary` | Domain steps: "Add {product} to the cart" as one step. Any plugin may offer it; see below. |

The first ready plugin with a `host` is the app plugin: only its `inventory`, `app` and `export` are used. Hooks and a vocabulary work from any plugin, so a second plugin can add only those, and a plugin can offer only a vocabulary (`new PluginParts(vocabulary)`).

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

App steps refuse to run while `openDialog()` reports a dialog that waits for an answer. `openDialog()` has a default: the modal dialog a person answers next, when it shows an option pane (`JOptionPane`), read the way a step's `pending` is: its title, its message one line per line, and the buttons that answer it (a button inside the message, such as "Details…", is not an answer). Override it only when your app asks its questions in dialogs of its own.

## AppHooks

Called on an HTTP thread, never on the event thread.

- **`reset(ResetSpec spec)`:** bring the app to one test's start state: its own dialogs closed, every tab closed, logged in as `spec`, settled. By the time it runs, the agent has already reset the pointer and closed modal dialogs with Escape. Throw, with the reason, when the app cannot get there. A dialog that will not close is a finding, not something to work around.
- **`recoverSession()`:** optional. Sign back in when the app's session expired behind a sign-in dialog. It runs before the dialogs are escaped, because Escape on such a dialog may only ask whether to exit.
- **`login(ResetSpec who, boolean force)`:** log a user in mid-test without closing anything.
- **`info()`:** the app's name and version for `session`.

`ResetSpec` is either an alias (`ResetSpec.alias("clerk")`; blank means `default`) or explicit credentials. Resolve an alias with `UiUsers.lookup(alias)`, which reads `.swing-ui-agent/ui-users.properties`.

## UiInventory

What `describe_ui` reports: `activeTabTitle()`, `navTargets()`, `entryPointIds()`, `toolbarActions()`, `menuPaths()`, `navEntryPoints()`, `viewTabs()`, `viewSections()` and `viewSectionProblems()`. Each one answers from the live app; return empty lists for what your app does not have.

The generic parts have defaults, read off the main window (the first showing frame, or the one `driver.setMainWindow(…)` names):

- `menuPaths()`: every leaf of the menu bar as a `menu` path (`"Orders > New order"`), one that cannot be chosen marked `[disabled]`.
- `viewSections()` and `viewSectionProblems()`: the sections registered with `driver.registerSections(…)`, one row each (`id`, `name`, `created`, `state`, and `details` when there are any), and what went wrong reading them.

Override them to report more or less; `UiInventory.super.menuPaths()` gives you the default to filter.

## Teach the driver about your app

`context.driver()` takes what only your app knows. Register it in `start`, before you return:

```java
Driver driver = context.driver();
driver.setMainWindow(OrderDesk::mainWindow);           // the default is the first showing frame
driver.registerBusySignal(new OrderDeskBusy());        // every step waits until it is quiet
driver.registerSections(new OrderDeskSections());      // named areas: snapshot, targets, describe_ui
driver.addErrorDialogClassifier(new OrderDeskCrash()); // its dialogs fail a step as APP_ERROR
driver.registerParts(new SegmentedSwitchParts());      // what a custom component paints inside itself
```

- **Sections.** A `SectionSource` names areas of a screen. A titled panel keeps its caption and stays
  `role:"section"`; a plain panel without a caption is reached by its section `id` only
  ([Targets](/swing-ui-agent/guides/targets/#sections)).
- **Painted parts.** A `PartSource` lists what one component paints and a user still picks on its own, such as the
  segments of a switch: a `Part` per piece, with a role, the word it shows, its state and its area. The driver asks it
  for every `clickable` component, so answer fast and return `null` for a component you do not know. A part is then
  a target by role and name, like any other element.
- **Busy signals.** After acting, a step waits until every signal is quiet, and still quiet once the event queue has run again: an app that ends one job and queues the next has a quiet moment between the two.
- **Error dialogs.** A step that leaves a modal dialog returns it as `pending`, for the next step to answer. A dialog an error classifier recognises fails the step as `APP_ERROR` instead. There is no rule by default: many apps use an error-type message for an input they refuse, which a test answers on purpose. Rules add up, so several plugins can each add theirs. If every error-type option pane in your app reports a failure, add the ready-made rule: `driver.addErrorDialogClassifier(ErrorDialogs.ERROR_OPTION_PANE)`.

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

## Vocabulary

```java
public interface Vocabulary {
    List<DomainStep> steps();   // read once, when the plugin becomes ready
}
```

A `DomainStep` has an id, a sentence with `{param}` placeholders, its params and a body that does the step through the same `Client` a UI test uses. `steps()` is read once, on the plugin's start thread. A step that fails its checks is dropped and listed under `vocabularyProblems` in `session`; the others stay and the plugin stays ready. The steps are served by the `vocabulary` tool and `do_action`, folded from recordings, and called from Java tests. A vocabulary adds no `host`, so the agent's own reset stays. How to write steps, patterns and typed calls: [Plugin vocabulary](/swing-ui-agent/plugins/vocabulary/).

`DomainStep.Body` takes a `Client`, which lives in `io.github.kormcrete.swingui.junit`: that is the one class outside `spi` and `driver` the SPI uses.

## A sketch

Order Desk's own plugin offers a reset (its `OrderDeskApp` is the `host` and the `app`: it closes every order tab and shows the Orders list), an `inventory` for `describe_ui` (the selected tab, the toolbar buttons and an open order's own tabs; menu paths come from the default) and a vocabulary ([Plugin vocabulary](/swing-ui-agent/plugins/vocabulary/)). This is the shape of a plugin with every app part, navigation and describe_ui too:

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
- [Plugin vocabulary](/swing-ui-agent/plugins/vocabulary/): domain steps.
- [Agent options](/swing-ui-agent/reference/agent-options/): `plugins=`
- [Security](/swing-ui-agent/agents/security/): what `plugin_call` can reach.
