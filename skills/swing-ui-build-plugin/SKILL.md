---
name: swing-ui-build-plugin
description: Builds, completes or updates a Swing app's swing-ui-agent plugin from the app's source, for the app's developer. Surveys every screen into a coverage list, writes domain steps and checks with unit tests, proves them with an eval run by a fresh agent that has no source, and fixes until the bar is met. Use when asked to write, complete or update the plugin for my app, or when the app changed and its plugin must follow.
---

# Build the app's plugin

You work for the app's developer and you have the source. The testers do not: they have the built app, its plugin
and a coding agent with swing-ui-agent's skills. The plugin is all their agent knows about the app. When a tester
says "place an order for Ada Shop Ltd: 2 desk lamps, 1 notebook, and check the line total and that it shows as
Placed", their agent must know from the plugin alone which domain steps and checks to run, in which order, with
which values. It runs the request as one `batch`, never scans the app with `snapshot`, and reports the result (the
order number) or why it could not (an unknown customer) in the tester's words.

Your job: a plugin that is **complete** (every action and value a person has is covered, or is a gap with an
accepted reason), **honest** (it acts and reads the way a person does) and **proven** (a fresh agent without the
source runs a tester's scenarios with it). The examples come from Order Desk, a small order-entry app whose plugin
was built this way; use your app's own words.

## Your project's part

The project's instructions (its `CLAUDE.md`, `AGENTS.md` or the like) name these; they win over the defaults here.

- **Agent command**: how to call the CLI, with the agent jar's path and the port you use for your own checks.
  Default `java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar` on port 8765.
- **Plugin module**: where the plugin's code lives. Default: propose a project of its own beside the app (Order
  Desk: `order-desk-plugin/`) that depends on swing-ui-agent with scope `provided`; ask before you create it.
- **Coverage list**: default `COVERAGE.md` in the plugin module (format: `references/coverage-list.md`).
- **Building and testing**: how to build the plugin and run its unit tests, and how to start the app with the
  plugin (`-javaagent:<agent jar>=port=<p>,plugins=<plugin jar or classes dir>`). Default: ask.
- **Who approves**: the person you work for approves every change a user of the app could see.

## Done means

Both, never one alone:

1. **The coverage list is complete**: every row is covered by a step, a check or the reset, or is a gap with one of
   the three accepted reasons.
2. **The eval bar is met** (`references/eval.md`): all scenarios pass, with no `snapshot`, no `screenshot` used to
   find the way, one `vocabulary` round per session and at most 2 driving calls per scenario.

A complete list needs more steps and checks than the scenarios alone would. That is the point: testers will ask for
things no scenario thought of.

## The loop

| Phase | You make | Exit check |
|---|---|---|
| 1. Survey | the coverage list, from the source, reach confirmed on the live app | the list exists; every row is covered, "to write" with its locator (or a reach fix) or a gap with an accepted reason; no unexplained gap |
| 2. Write | steps, checks, reset, unit tests; reach fixes in the app | all unit tests green; no "to write" row left; live check passes (below) |
| 3. Prove | the eval: a scenario writer, then a fresh runner | the bar is met |
| 4. Fix | fixes to the plugin, the app or this list | back to 2 (or 1 when the survey missed something); after 3 eval rounds without the bar, stop and report |

When the app changes, run the same loop on the change (see "When the app changes"). A plugin that no longer matches
the app, without anyone noticing, is a failure.

## 1. Survey

Read the app's source screen by screen and write one row per thing a person can do or see:

- **Screens**: every window, dialog, tab, wizard step and popup, and how a person gets from one to the next.
- **Actions**: every menu item, toolbar button, button, context menu item, key binding, double click and editable
  cell. Look for `ActionListener`s, `Action`s, mouse and key listeners, `JPopupMenu`s, cell editors.
- **Values**: everything a person reads: labels next to captions, table columns, totals, status bars, tab and
  window titles, enabled and disabled states, messages.
- **What the app makes**: numbers, dates, names it generates (Order Desk: `ORD-1640`).
- **What can go wrong**, in the user's terms: validations, refusals, confirmations, error messages ("Checkout… stays
  disabled while a cart line asks for more than is in stock").

Write each row as a user would say it ("Add a product to the cart"), never as code ("NewOrderPanel.addSelected").
Rows already covered point to their step; the rest are "to write". A row becomes a gap only with an accepted
reason. Format and the three reasons: `references/coverage-list.md`.

Then **confirm reach on the live app** for every "to write" row: start it with the agent, find the locator the step
will use with scoped `read`, `expect` or `snapshot` (the swing-ui-targeting skill; never an unscoped snapshot), and
note it. A row whose element cannot be reached honestly goes to "Reach fixes needed".

## When to change the app

Some things a plugin cannot reach honestly through the UI: a field with no name and no caption, a value only a
renderer paints, two buttons with the same text, an app bug that blocks the step. Fix those in the app, in small
ways:

- **Invisible first**: a component name (`setName("cartTotal")`), so a locator has an `id`. No user sees it.
- **Visible only with the person's OK**: a caption, a status text, a changed message. Ask, wait for the answer, and
  then update what the change touches: screenshots, docs, tour pages, bundled recorded tests.
- **A bug**: tell the person what you found and fix it only if they agree. Never work around it in a step.
- Every change goes into the list's "Changes made to the app", with its row and whether it is visible.

Never change what the app does so a step gets easier, and never add a back door for the plugin.

## 2. Write the plugin

The parts (the Plugin SPI and vocabulary pages: https://kormcrete.github.io/swing-ui-agent/plugins/spi/ and
https://kormcrete.github.io/swing-ui-agent/plugins/vocabulary/):

- **Reset** (`AppHooks.reset`, with a `StepHost`): one defined start screen for every test, reached through the UI
  (close every tab, answer the app's "discard?" with Discard). The app's data stays: tests create what they check.
- **Login and navigation** (`StepHost.loginAs`, `navigate`), where the app has them.
- **Inventory** (`UiInventory`) for `describe_ui`.
- **Vocabulary** (`Vocabulary.steps()`): a `DomainStep.act` per action, a `DomainStep.check` per kind of value.
- **An overview** of the app (its screens, the life cycle of its main record, what its nouns mean), if the plugin
  interface has a place for one. Check the SPI; until it has one, the `about` lines carry it.

Write each name the app shows once, as a constant in the steps class (Order Desk: `OrderDeskSteps`); the bodies,
the `match` patterns and the typed Java calls use it. Write the steps screen by screen, one commit per screen.

### Rules for every step and check

1. **The sentence is what a tester would say**: "Add {qty} × {product} to the cart", "The cart total is {total}".
   It names the goal, not the button.
2. **One parameterised step instead of near-duplicates.** A value the person chooses is a param, never a step per
   value. Few, well-named steps; a vocabulary the agent cannot choose from is a failure.
3. **`about` says where the step fits.** An act step: `Needs: <what must show first>. Ends: <where it leaves the
   app>.` Order Desk's checkout: "Needs: a new order with something in its cart. Ends: on the checkout's Review
   step, before Place order." A check: what it reads, with an example value. If the plugin interface gains fields
   for what a step needs and where it ends, use those instead of the text.
4. **Act through the control a user would use; read what the user sees** (swing-ui-targeting, "The rule"). The body
   makes `Client` calls only: no app classes, no app models, no reflection, no accessibility helpers created on
   the app's components, no hook that clicks. A step that calls app internals to "place an order" passes while the
   Place order button is broken.
5. **Every check reads what the user sees** and, when the value is wrong, fails at once with the expected and the
   actual value in the tester's words, not after a 10 s timeout when the value is already settled and different.
6. **Values a tester gives by name** (a customer, a product): accept the whole name or its start, any case, when
   exactly one fits ("Ada Shop", "desk lamp"); when none or several fit, fail naming the values that fit. Give the param
   `choices` read from the live app.
7. **Fail fast, in words, when the screen the step needs is not showing**: within about a second, naming what it
   needs and the step that gets there ("No new order is open: open one first with Open a new order for
   {customer}"). Look for it with a short timeout (`ui.timeout(Duration.ofSeconds(1))`), then throw an exception
   whose message is that sentence: the step fails with it.
8. **Two things that match**: the step says which one it takes (the cart line, the newest order) or fails as
   ambiguous, naming both. It never takes one by chance.
9. **What a step makes is its outcome**: a body that makes a value the screen does not show whole (a new order's
   number inside "Order ORD-1640 placed") returns it with `codeWithOutcome`, so later steps link to it. Return
   a value by name or in a map keyed by a stable name (`orders` keyed by order number): a value found only by its
   position in a returned list is never linked.
10. **Every step has its Java form** (`java(...)`), and a `match` where a person's recorded clicks should fold into
    it. A check, and any step whose body reads the app (a fail-fast look counts), yields no pattern from its body:
    give it a `match`.
11. **A unit test per step**, in the plugin module: the steps its body takes, traced on a `Client` that reaches no
    app (`TracingVerbs`, as Order Desk's `OrderDeskStepsTest` does), plus the failures of rules 5 to 8: a partial
    name that resolves or names the choices, `<step>FailsFastWhenItsScreenIsNotShowing`, `<step>OnTwoMatches`,
    `<check>FailsWithExpectedAndActual`.

### Exit check for phase 2

- Every unit test is green; no row is "to write".
- Live: start the app with the plugin; `session` shows it `ready` with no `vocabularyProblems`; `vocabulary
  {"op":"list"}` lists every new step; each new step and check runs green once in a `batch` from the screen its
  `Needs` names.
- The app's own recorded tests, if it bundles any, still pass (`run_recorded_test`), or were updated on purpose.

## 3. Prove it

A separate agent writes scenarios the way a tester would, from what a tester has (the app's README and the running
app), never from the plugin or the source. Then ONE fresh session with no source runs them all, set up as a tester
would have it, and you measure from the app's timing log. How to set up the writer, the runner, the lock-down that
keeps the source out of reach and the counts: `references/eval.md`.

The person reviews the scenario set before the first round. The set is then fixed: later rounds may add scenarios,
never drop a failing one.

## 4. Fix and repeat

For every failed check, every `snapshot`, every extra driving call, find the cause and write it down:

- **Plugin gap**: a step or check is missing, its `choices` are wrong, its `about` did not say what it needs or
  where it ends, its failure did not name the reason. Fix the plugin; add the row if the survey missed it.
- **Step bug**: fix it, with a unit test that failed first.
- **App reach or app bug**: as in "When to change the app".
- **Wrong expectation**: correct a scenario's expectation only when the app shows it was wrong; note the evidence
  and list every correction for the person.

Then run the whole set again in a new round. After 3 rounds without the bar, stop and report what still fails and
why, instead of looping.

## When the app changes

Note in the coverage list the app commit it was surveyed at. When the app changes, survey the diff since then
(`git diff <commit>`): new, changed and removed actions and values. Update the rows, the steps and their tests, run
the eval again with the same scenario set (plus scenarios for what is new), and note the new commit.

## Rules that hold even when the numbers pass

- **No cheating.** Steps and checks act through the controls a user uses and read what a user sees. The harness
  must not change what the app does.
- **No scenarios written to the plugin.** The writer never sees the plugin or the source, and the app runs without
  the plugin while it writes.
- **No vocabulary the agent cannot choose from**: hundreds of near-duplicate steps. Coverage comes from few,
  parameterised steps whose sentences a tester would say.
- **No refusals.** A runner that refused a request because the plugin lacked it failed that scenario. A step that
  cannot do what was asked fails with the reason, in words.
- **No silent drift.** The coverage list and the eval are run again whenever the app changes.

## Report

To the person: the coverage list (rows covered, gaps with reasons), the steps and checks added or changed, every
change made to the app (visible ones with their OK), the eval rounds (per scenario: pass or fail per stated check,
snapshots, screenshots, driving calls, time), every expectation you corrected and why, and what is still open.
