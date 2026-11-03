---
name: swing-ui-build-plugin
description: Builds, completes or updates a Swing app's swing-ui-agent plugin from the app's source, for the app's developer. Surveys every screen into a coverage list, writes domain steps and checks with unit tests, proves them with an eval run by a fresh agent that has no source, and fixes until the bar is met. Use when asked to write, complete or update the plugin for my app, or when the app changed and its plugin must follow.
---

# Build the app's plugin

You work for the app's developer and you have the source. The testers do not: they have the built app, its plugin
and a coding agent with swing-ui-agent's skills. The plugin is all their agent knows about the app. When a tester
says "place an order for Ada Shop Ltd: 3 packs of A4 paper and 10 black gel pens, paid with PO 7781, and check
the review total and that it shows as Placed", their agent must know from the plugin alone which domain steps and
checks to run, in which order, with which values. It runs the request as one `batch`, never scans the app with
`snapshot`, and reports the result (the order number) or why it could not (an unknown customer) in the tester's
words.

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
2. **The eval bar is met** (`references/eval.md`): all scenarios pass, with no snapshot (also none inside a batch),
   no `screenshot` used to find the way, one `vocabulary` round per session and at most 2 driving calls per
   scenario (saving and re-running a test, and a picture the scenario asks for, are counted apart).

A complete list needs more steps and checks than the scenarios alone would. That is the point: testers will ask for
things no scenario thought of.

## The loop

| Phase | You make | Exit check |
|---|---|---|
| 1. Survey | the coverage list, from the source, reach confirmed on the live app | the list exists; every row is covered, "to write" with the calls its step will make (or a reach fix) or a gap with an accepted reason; every existing step checked against rules 5-9; no unexplained gap |
| 2. Write | reach fixes in the app (invisible ones first, visible ones after the OK), then steps, checks, reset, unit tests | all unit tests green; no "to write" row left; live check passes (below) |
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
- **What a tester asks to be told**, beside what they change: the text of every question and confirmation (and each
  answer), an About box, the name a file chooser suggests, which radio is chosen, a disabled control's reason, what a
  list shows (how many, which, in what order). A tester's agent reads these by a check, never by a snapshot; a row
  missed here costs a snapshot in every eval round.

Write each row as a user would say it ("Add a product to the cart"), never as code ("NewOrderPanel.addSelected").
Rows already covered point to their step; the rest are "to write". A row becomes a gap only with an accepted
reason. Format, the three reasons and the rows that fit neither: `references/coverage-list.md`.

**Existing steps are not taken on trust.** Read each one's body against rules 5 to 9 below (does it fail with the
actual value, resolve a name or fail naming the choices, fail fast off its screen, take one of two by chance, leave
the screen as it found it?) and write what breaks them under "Notes for the steps" in the list; phase 2 fixes
them.

Then **confirm reach on the live app** for every "to write" row: start it with the agent, find the target and the
calls the step will make with scoped `read`, `expect` or `snapshot` (the swing-ui-targeting skill; never an unscoped
snapshot), and note them. A row whose element cannot be reached honestly goes to "Reach fixes needed". What counts:

- **A label with no caption** (a status line, a reason beside a disabled button, a field's error) is reached by its
  `id` when the app named it, else when a fixed part of its text is unique on its screen
  (`"name":"open orders","match":"contains"`), so a check can fail naming the actual text. When neither holds, it
  needs a name: a reach fix.
- **Two unnamed tables on one screen** that share a column (products and cart, both with Product) are told apart by
  a column only one has: a row locator names it too (`{"SKU":…}` or `{"Line total":…}`), or a click is AMBIGUOUS.
- **Enabled states and popup items**: `describe_ui {"fields":["menuPaths"]}` marks a disabled menu item
  `[disabled]` (`read {"form":"menuItems"}` lists a menu bar menu without enabled states). A popup's items (a
  toolbar dropdown, a row menu) show in the failure of a deliberately wrong `pick` or `context_menu` item, which
  fails in about a second and lists them.

## When to change the app

Some things a plugin cannot reach honestly through the UI: a field with no name and no caption, a message whose
text varies with no fixed part to find it by, a value only a renderer paints, two buttons with the same text, an
app bug that blocks the step. Fix those in the app, in small ways, at the start of phase 2, before the steps that
need them:

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

How a tester's agent uses them shapes them: it reads the vocabulary once, maps a whole request to steps and checks,
and runs it as ONE `batch` (with `continueOnFailedCheck` when it must tell values: a failed check is reported and the
batch goes on). It chains steps by their `about`, guesses a value it must tell and reports the actual one from the
check's failure message, and never looks at the screen to find its way. So every value a tester may ask for needs
a check, every check's failure must carry the value, and every `about` must say where the step starts and ends.

### Rules for every step and check

1. **The sentence is what a tester would say**: "Add {qty} × {product} to the cart", "The cart total is {total}".
   It names the goal, not the button.
2. **One parameterised step instead of near-duplicates.** A value the person chooses is a param, never a step per
   value; the same for checks: one check per kind of value (any cart line and column), not one per field. Few,
   well-named steps; a vocabulary the agent cannot choose from is a failure.
3. **`about` says where the step fits.** An act step: `Needs: <what must show first>. Ends: <where it leaves the
   app>.` Order Desk's checkout: "Needs: a new order with something in its cart. Ends: on the checkout's Review
   step, before Place order." A check: what it reads, with an example value ("e.g. “Order ORD-1640 placed”"): the
   agent's first guess comes from it. If the plugin interface gains fields for what a step needs and where it ends,
   use those instead of the text.
4. **Act through the control a user would use; read what the user sees** (swing-ui-targeting, "The rule"). The body
   makes `Client` calls only: no app classes, no app models, no reflection, no accessibility helpers created on
   the app's components, no hook that clicks. A step that calls app internals to "place an order" passes while the
   Place order button is broken.
5. **Every check reads what the user sees** and, when the value is wrong, fails at once with the expected and the
   actual value in the tester's words, not after a 10 s timeout when the value is already settled and different.
   The actual value comes whole: an agent that must tell a value runs the check with a guess and reports what the
   failure says. Take the value as a tester gives it: the whole text or one part of a dotted line ("Account" of
   "Account · Invoice 30 days"), any case; `none` (or "") for "not shown"; the app's own empty text ("No customer
   matches") counts as none. Several values in one param are parted by `;` (names may hold commas).
6. **Values a tester gives by name** (a customer, a product): accept the whole name or its start, any case, when
   exactly one fits ("Ada Shop", "desk lamp"); when none or several fit, fail naming the values that fit. Resolve
   against what the app offers (its suggestions, its list) by this rule even when the app's own search is looser
   (anywhere in the name, the city too). Give the param `choices` read from the live app; when they can be narrower
   than what the step accepts, `about` says so.
7. **Fail fast, in words, when the screen the step needs is not showing**: within about a second, naming what it
   needs and the step that gets there ("No new order is open: open one first with Open a new order for
   {customer}"). Look for it with a short timeout (`ui.timeout(Duration.ofSeconds(1))`), then throw an exception
   whose message is that sentence: the step fails with it.
8. **Two things that match**: the step says which one it takes (the cart line, the newest order) or fails as
   ambiguous, naming both. It never takes one by chance.
9. **Leave the screen as you found it** wherever the step is not meant to move on. A check that has to open
   something to read it (a question it then answers Cancel or Keep, a file chooser it cancels) puts back the tab
   and the selection it found, pass or fail; a step that fails part way closes what it opened where it can. No
   step ends the app under test.
10. **An act the app may refuse** (adding an out-of-stock product) gets a step that sets it up without pressing
    (select the product), so the agent can check the refusal (the button disabled, with its reason) in the same
    batch: a failed act stops the batch.
11. **What a step makes is its outcome**: a body that makes a value the screen does not show whole (a new order's
    number inside "Order ORD-1640 placed") returns it with `codeWithOutcome`, so later steps link to it. Return
    a value by name or in a map keyed by a stable name (`orders` keyed by order number): a value found only by its
    position in a returned list is never linked. A check's outcome is always empty, so what a list shows (to tell
    or to compare) is returned by the acts that change it (search, filter, sort), plus an act that only goes to the
    list and returns it (Order Desk: `showOrders`). In a body, read an outcome with `Outcome.text(path)` or
    `number(path)` (they wait for a late value), not `json()` or `read()`: the agent jar relocates their JSON
    classes, so a plugin built against the plain library fails to link them.
12. **Every step has its Java form** (`java(...)`), and a `match` where a person's recorded clicks should fold into
    it. A check, and any step whose body reads the app (a fail-fast look counts), yields no pattern from its body:
    give it a `match`. A written pattern never names `window` (patterns do not compare it yet: `session` lists such
    a step under `vocabularyProblems`); write down in the coverage list which window it was meant for. A pattern on
    a table row or cell names a column only its table has, or it fits the other table on the screen too. A click
    that is the first part of another step (select a product, then Add to cart) only suggests its step
    (`autoFold(false)`): folded at once, it would take the click the longer step needs.
13. **A unit test per step**, in the plugin module: the steps its body takes, traced on a `Client` that reaches no
    app (`TracingVerbs`, as Order Desk's test helper `FakeApp` does), plus the failures of rules 5 to 9: a partial
    name that resolves or names the choices, `<step>FailsFastWhenItsScreenIsNotShowing`, `<step>OnTwoMatches`,
    `<check>FailsWithExpectedAndActual`, and the screen put back after a failure.

### Exit check for phase 2

- Every unit test is green; no row is "to write".
- Live: start the app with the plugin; `session` shows it `ready` with no `vocabularyProblems` (a dropped step, or a
  kept one with a note such as a pattern naming a window); `vocabulary {"op":"list"}` lists every new step; each new
  step and check runs green once in a `batch` from the screen its `Needs` names, and each check also fails once
  with a wrong value, naming the actual one.
- The app's own recorded tests, if it bundles any, still pass (`run_recorded_test`), or were updated on purpose.

## 3. Prove it

A separate agent writes scenarios the way a tester would, from what a tester has (the app's README and the running
app), never from the plugin or the source. Then ONE fresh session with no source runs them all, set up as a tester
would have it, and you measure from the app's timing log. How to set up the writer, the runner, the lock-down that
keeps the source out of reach and the counts: `references/eval.md`.

The person reviews the scenario set before the first round. The set is then fixed: later rounds may add scenarios,
never drop a failing one.

## 4. Fix and repeat

For every failed check, every snapshot, every raw verb, every extra driving call, find the cause in the transcripts
(what the runner read it for, what it called instead) and write it down:

- **Plugin gap**: a step or check is missing, its `choices` are wrong, its `about` did not say what it needs or
  where it ends, its failure did not name the reason. Fix the plugin; add the row if the survey missed it. A raw
  verb is a gap even when the runner wrote no "Plugin gap:" line for it.
- **Step bug**: fix it, with a unit test that failed first.
- **App reach or app bug**: as in "When to change the app".
- **Tester habit**: a step existed and the runner did not use it, or it split one request over several calls. Make
  the step easier to find (a sentence in the tester's words, an `about` with the texts the app shows); a habit the
  tester skills cause goes to swing-ui-agent as a finding, not into the plugin.
- **Known limit**: a value computed from another ("the count goes up by one") belongs in a Java test, never in a
  recorded test; do not add plugin state for it. Note it for the person.
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
