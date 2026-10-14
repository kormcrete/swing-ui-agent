---
title: Record a test by showing it
description: Use the app while a palette in its window records your steps and checks, then let the agent export the test.
sidebar:
  order: 7
---

Sometimes it is quicker to show the agent what a test should do than to describe it. Start the app with
the agent and a small palette appears in its window. You press **● Record** and use the app. Every click,
selection and bit of typing becomes a step, as if the agent had run it. **Check** adds checks, **Steps** lets you
replay and fix them, and **Done** ends the recording. Then **Export…** writes it as a `@UiTest` class, or your coding
agent does.

![The palette in Order Desk while recording: a red dot, the word Recording, the step just recorded, and the Record, Check, Steps and Done buttons](../../../assets/inspector/recording.png)

You will learn how to:

- start the app with the palette;
- record steps and checks;
- replay, redo and delete steps;
- export it as a JUnit class, yourself or through the agent;
- answer the agent when it asks about the app.

## Before you start

- The app is started with the agent, as in [Installation](/swing-ui-agent/start/install/).
- Your coding agent can call the CLI and has the [skills](/swing-ui-agent/agents/skills/) installed. The
  `swing-ui-record-test` skill knows this workflow.
- You are at the screen. The palette is for a person working with the agent. Leave it off for agent-only and CI
  runs.

## 1. Start the app with the agent

The palette is on by default, so there is nothing to add to the agent arguments:

```sh
java -javaagent:target/swing-ui-agent-agent.jar=port=8770 \
     -Dorderdesk.today=2026-10-12 -jar target/order-desk.jar
```

The palette appears at the bottom right of the main window, about a second after the window shows. Drag it by its
grip if it covers something you need. It is part of the window, not a separate one, so it also works when the app is
full screen.

- `palette=off` turns it off, for agent-only runs, CI and shared machines. Then no palette appears and nothing listens to input.
- `mode=background` turns it off too, and `palette=on` with it is refused: the palette needs the app in front of you. A headless run has no palette either.
- `session` shows `"palette": true`, which is how the agent knows it can ask you.
- While a test run holds the lease, the palette leaves the window; it comes back when the run ends.

## 2. Record the steps

Get to the screen where the test should start, then press **● Record**. A red pulsing dot and the word
**Recording** show that it is on.

Use the app as you normally would:

- **Clicks and double clicks** are recorded on the element you click.
- **Check boxes and toggle buttons** become a `check` step with the state they end in.
- **Typing** is recorded when you leave the field or press Enter, and only when you typed: text the app filled in is
  not a step.
- **Combo box items** become a `select` step.
- **Menus**, from the menu bar or the macOS screen menu bar, and their shortcuts, become a `menu` step.
- **Context menus** become a `context_menu` step.
- **Key chords** such as Escape or ⌘W become a `press` step.

The recording starts from the screen you are on. A test has to get to its own starting screen from anywhere, so make
the first step one that does: a menu item or a shortcut. In Order Desk that is **File ▸ New order**. When the first
step is a click instead, the agent's export comes back with a `startWarning`, and the agent will suggest a better
first step.

## 3. Add checks

Press **Check**. The bar says "Click what to check, then choose the check." Clicks now go to the palette, not to the
app. Click an element and pick a check built from what it shows right now.

![Check mode on an order's lines: the clicked SKU cell is boxed and a menu offers SKU is "PEN-GEL-BLK" in this row, Has exactly 3 rows and Has at least 1 row](../../../assets/inspector/check-menu.png)

Which checks you get depends on the element:

- **Labels and text:** reads "…", is empty, is editable or read-only.
- **Check boxes and radio buttons:** is checked, is not checked.
- **Tabs:** is the open tab.
- **Buttons and other controls:** is enabled or disabled, its tooltip.
- **Table rows and cells:** the cell's text, this row is selected, how many rows the table has.

Column checks such as "Every row's Status is …" and "Sorted by …" are shown greyed out: there is no check for them
yet.

Each check runs as soon as you choose it. A check that passes is added to the recording, and a green ✓ flashes on
the element. One that fails is not added: a red ✗ flashes there and the bar says "✗ not recorded". Press **Record** to
go back to recording steps.

## 4. Replay and fix the steps

Press **Steps** to open the list of what has been recorded so far, your steps and checks and any the agent ran.

![The Steps panel: the recorded steps with their results, the replay speed and the replay buttons](../../../assets/inspector/steps.png)

- **▶ Replay all** and **▶ From selected** run the steps again in the app. Choose the **Replay speed** first:
  Fast, Normal, Slow or Step by step.
- **Step by step** stops before each step: press **Next** to run it. An action and the checks that follow it run as
  one step. **Pause**, **Resume** and **Stop** work at every speed.
- **↺ Redo from selected** drops that step and everything after it, replays the steps before it, and records again
  from there.
- **✕ Delete** removes one step.

Results stay on the steps as ✓ and ✗ until the next replay. "Replay all" starts from the screen the app is on, so
bring the app back to the starting screen first.

## 5. Press Done, then export

Press **Done** when the flow is right. The bar shows **Recorded** and offers:

- **● Record:** a new recording, which replaces this one.
- **Steps:** this recording, to replay or fix. **↺ Redo from selected** records again from that step.
- **Export…:** writes the recording as a JUnit class, without an agent.

**Export it yourself.** Press **Export…**, enter a class name ending in `UiIT` and a package (filled in with the
app's own package), and press **Export**. The palette says where the file went, and when the first step does not get
the app to a known screen it says that too. The recording stays, so you can still fix it and export again under
another name. An existing file is never overwritten.

**Or let the agent export it.** When your agent is waiting for you, Done hands it the recording. It exports the
class, runs it from another screen and fixes the locators it flags, as in
[Record a test with the agent](/swing-ui-agent/start/record/):

```sh
UI="java -jar target/swing-ui-agent-agent.jar --port 8770"
$UI recording '{"op":"list","wait":540000}'          # returns at your Done: outcome done, with the steps
$UI export_test '{"class":"NewOrderForAdaUiIT","package":"io.github.kormcrete.orderdesk"}'
```

You start and end a recording, or ask your agent to.

Either way, run the new test once from another screen before you commit it. See
[Record and run a UI test in IntelliJ](/swing-ui-agent/start/intellij/) for that loop in the IDE.

## Answer the agent in the app

With the palette on, the agent can ask you about the app in the app itself instead of guessing:

- **Highlight:** the agent boxes everything a locator matches, numbered when there are several, and asks a question
  on a bar. You click the ✓ on the one you mean, or **None of these**. The agent gets the locator of your choice.
- **Pick:** the agent asks you to click what you mean. The click goes to the palette, not the app, and the agent gets
  a locator for it.

![The agent asks which table holds the cart: two numbered boxes with a check mark each, and a None of these button](../../../assets/inspector/agent-asks.png)

```sh
$UI inspect '{"op":"highlight","target":{"role":"table"},"question":"Which table holds the cart?"}'
$UI inspect '{"op":"pick","question":"Click the field that holds the customer"}'
```

`inspect` also has `hover`, which shows the locator under your pointer as you move it, and `clear`, which removes
the agent's boxes and question.

## You and test runs

You and a test run share the same app and agent:

- While you record, pick, answer or replay, a test run cannot take the agent: taking the lease fails as `BUSY`,
  with what you are doing as the reason.
- While a test run holds the agent, the palette leaves the window, so it never covers what the test clicks. It comes
  back afterwards.
- `session reset` is refused while you record, since it would wipe your recording.

## Known gaps

- The palette lives in the main window. While a modal dialog is open you cannot press its buttons, so add checks
  before it opens or after it closes. Your steps in the dialog are recorded.
- Column checks have no check kind yet.
- Spinner arrow clicks, password fields, drag and drop, and list or tree items are not recorded as steps yet.
- On Windows and Linux the palette is tested automatically, not yet by hand.

See [Limits](/swing-ui-agent/project/limits/) for the full list.

## What's next

- [Record a test with the agent](/swing-ui-agent/start/record/): the same result from a description.
- [Run the tests](/swing-ui-agent/start/run/).
- [Leases and several clients](/swing-ui-agent/guides/leases/): how a person and a test run share the agent.
