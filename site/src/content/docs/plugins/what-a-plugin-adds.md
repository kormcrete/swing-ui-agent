---
title: What a plugin adds
description: "Optional: teach the agent your app's own language."
sidebar:
  order: 1
---

Everything else on this site works on any Swing app as it is: no plugin, no changes to the app. A plugin is a small jar of your own that the agent loads into the app. It is for when you want the agent and the tests to speak your app's language, for example "log in as the clerk" or "open the orders screen".

## Without a plugin

- **Reset** puts the pointer back and closes open modal dialogs. Tabs, the logged-in user and data stay as they are.
- **The starting screen** is reached by each test itself, through the menu bar or a keyboard shortcut. `export_test` warns when a recording starts any other way. See [Reset and the starting screen](/swing-ui-agent/guides/reset/).
- **Login, navigation and app facts are gaps.** `@LoginAs`, `ui.loginAs(…)`, `ui.navigate(…)`, `describe_ui` and `plugin_call` fail with a clear error:

  ```text
  CAPABILITY_GAP: login needs the app plugin; no plugin configured (agent argument plugins=…)
  ```

Order Desk's tests need no plugin. Its plugin only adds a [vocabulary](/swing-ui-agent/plugins/vocabulary/), which makes some of them shorter.

## With a plugin

- **Reset to a defined state.** Before every test, the plugin brings the app to one known start: its own dialogs closed, every tab closed, logged in as the test's user. An expired session behind a sign-in dialog is recovered first.
- **Login.** `@LoginAs("clerk")` on a test class or method, and `ui.loginAs("clerk")` mid-test. Aliases come from `.swing-ui-agent/ui-users.properties`:

  ```properties
  # one alias=user:password per line; never commit this file
  default=clerk:secret
  clerk=clerk:secret
  manager=mia:secret
  # a secret of an alias: ui.secret("default", "pin")
  default.pin=4711
  ```

  The plugin turns an alias into a user and password with `UiUsers.lookup(alias)` in the app's JVM; `ui.secret(…)` reads a secret in the test's JVM. Both look for `.swing-ui-agent/ui-users.properties` in the working directory, then in each folder above it, so one file at the repository root serves every module. `-Dswingui.users=<path>` points elsewhere. A key with a dot is a secret, never an alias, and never appears in an error message.
- **Navigation.** `ui.navigate("orders")` opens your app's own screens by name or entry-point id, and `ui.closeActiveTab()` closes the open one.
- **App facts for the agent.** `describe_ui` answers with the active tab, the navigation targets, toolbar actions, menu paths, the open view's tabs and its sections. One call instead of several snapshots.
- **Your own hooks.** An interface of your own that tests call as `ui.hooks(OrderDeskHooks.class).openTabTitles()`, and agents call with `plugin_call`. Use hooks for what a test needs to know or prepare, never to skip the UI it is testing.
- **A vocabulary.** Your app's own steps, such as "Add Gel pen, black to the cart": the agent runs one in a single call, a recording folds the clicks that make it into one step with its sentence, and a Java test calls it as a method. It needs no login or navigation. See [Plugin vocabulary](/swing-ui-agent/plugins/vocabulary/).
- **Where tests go.** A default module folder and package for `export_test`.

Every part is optional. Reset, login, navigation and app facts come from the one app plugin; any plugin can add hooks or a vocabulary. See the [Plugin SPI](/swing-ui-agent/plugins/spi/).

## Load a plugin

Add `plugins=` to the agent options, with the plugin jar or class folder:

```sh
java -javaagent:swing-ui-agent-0.1.0-SNAPSHOT-agent.jar=plugins=order-desk-plugin.jar -jar order-desk.jar
```

Several plugins are separated by `:` (`;` on Windows). The agent starts each one once the app class it names has loaded, so a plugin can rely on the app's own classes. `session` lists every plugin with its state: `waiting`, `starting`, `ready` or `failed`, with the reason. A plugin that fails leaves the generic tools working. A test that needs a configured plugin which is not ready fails at once with that reason.

## What's next

- [Plugin vocabulary](/swing-ui-agent/plugins/vocabulary/): domain steps.
- [Plugin SPI](/swing-ui-agent/plugins/spi/): the interfaces a plugin implements, and how to package it.
- [Reset and the starting screen](/swing-ui-agent/guides/reset/)
- [Agent options](/swing-ui-agent/reference/agent-options/)
