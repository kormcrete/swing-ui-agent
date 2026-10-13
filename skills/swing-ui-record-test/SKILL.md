---
name: swing-ui-record-test
description: Records a JUnit 5 @UiTest class for a Swing app by driving the live app through swing-ui-agent one step at a time, exporting the recording with export_test, and proving the test passes from another screen. Use when asked to write, record or capture a UI test (a *UiIT class) for an app running with the swing-ui-agent Java agent.
---

# Record a UI test

You drive the real app, swing-ui-agent records each step, and `export_test` writes the steps as a `@UiTest` class.
The user says what the test must prove. You choose the steps; the user chooses which checks matter. Ask when that
is unclear; do not invent assertions. Tests are written this way, by the agent; a human reviews and runs them.

## Setup

The app runs with `-javaagent:swing-ui-agent-<version>-agent.jar`. Every tool is one shell call:

```sh
UI="java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar"
$UI session                 # agent up? which plugins, in which state
$UI tools                   # every tool; `$UI tools expect` prints one tool's schema
```

The same tools are served over MCP at `http://127.0.0.1:8765/mcp`.
Exit codes: 0 ok, 1 tool error (message on stderr), 2 usage, 3 no agent reachable (the app is not running with the
agent: stop and say so). A step that ran and failed exits 0 with `"passed":false`, so always read `passed`.

`busy: leased by <owner> since ...` means a test run holds the app. Wait for it. If that run is dead, retry once the
agent has been idle 15 s, or take the lease: `$UI session '{"op":"lease","owner":"recorder"}'`, pass
`--lease <token>` before the tool name on every call, and `session '{"op":"release","token":"<token>"}'` at the end.

## How recording works

- `$UI session '{"op":"reset"}'` starts a fresh recording. Every successful step with a Java form after it is recorded.
- Reads are never recorded: `snapshot`, `read`, `screenshot`, `describe_ui`, `read_clipboard`, `read_file_chooser`.
- `export_test` refuses a recording that did not begin at a reset. After an export (`clear` defaults to true),
  reset again before recording the next test.
- A test replays from the state right after a reset. Without an app plugin a reset only closes modal dialogs, so
  the app may be on ANY screen. The recording must BEGIN by reaching its own starting screen from anywhere: the
  menu bar (`menu`), a keyboard shortcut (`press`), or with a plugin `navigate`. `export_test` answers with a
  `startWarning` when a plugin-less recording begins otherwise.
- With an app plugin, its reset brings the app to a defined start state (tabs closed, logged in);
  `{"op":"reset","alias":"clerk"}` picks the user from `.swing-ui-agent/ui-users.properties`.
- Mistakes are recorded too: a wrong click followed by Escape is two steps of the test.

## Steps

1. **Explore with reads first.** Find the route and every target before recording: scoped `snapshot`, `read`
   (see the swing-ui-targeting skill). Nothing you read lands in the test.
2. **Reset:** `$UI session '{"op":"reset"}'`.
3. **Reach the start screen** as the first step: `$UI menu '{"path":["Orders","New order"]}'`.
4. **Act.** Read each result: `passed`, `message`, `recorded` (the step as it will be exported), `notRecorded`
   (it ran but has no Java form, and why), `dialog` (a message waits: `answer` it). Once the next steps are
   certain, send them as one `batch`; it stops at the first step that does not pass:
   ```sh
   $UI batch '{"steps":[
     {"verb":"type","target":{"role":"text","name":"Customer"},"text":"Acme"},
     {"verb":"click","target":{"role":"button","name":"Save"}}]}'
   ```
5. **Check with `expect`**, so the assertion is recorded. It retries until its timeout (default 10 s). Give exactly
   one of `state`, `text`, `value`, `tooltip`, `rows`, `cell`, `selected`:
   ```sh
   $UI expect '{"target":{"role":"table","name":"Orders"},"cell":{"row":{"Customer":"Acme"},"column":"Status","expected":"Open"}}'
   $UI expect '{"target":{"role":"table","name":"Orders"},"rows":{"op":"gte","value":1}}'
   $UI expect '{"target":{"role":"button","name":"Save"},"state":["!disabled"]}'
   ```
6. **A recorded step was wrong?** Do not patch it with more steps. Reset and redo from step 3.
7. **Export, then verify** (below).

## When a person records with you

If `$UI session` shows `"inspector": true` (the app was started with `inspector=on`), the person at the app can show
you instead of describing:

- **Ask, don't guess.** When a target is unclear, `$UI inspect '{"op":"highlight","target":{...},"question":"Is this the
  Customer field?"}'` boxes every match in the app; the answer names the one they confirmed and its locator
  (`outcome` `confirmed` / `rejected`). `{"op":"pick","question":"Click the field that holds the customer"}` returns the
  locator of what they click.
- **They record, you export.** The person presses ● Record in the palette in the app window, uses the app (every step
  is recorded as if you had run it), adds checks in Check mode, replays or fixes steps, and presses Done. You never
  start or end it: `$UI inspect '{"op":"recording","wait":540000}'` waits for their Done (`outcome` `done`, or
  `recording` while they are still at it, `idle` before they started) and returns the recording. Then export as below
  and verify as always: their steps were recorded, not re-run, until a replay. While they record, `session reset` is
  refused (it would wipe their recording).
- **Or they export.** After Done the palette offers Export…: the person names the class and package and the file is
  written without you (the recording stays). When they ask you to check it, run it from another screen and fix what
  it flags, as in "Export, then verify".
- With `"inspector": false` the tool answers CAPABILITY_GAP; work from the person's words as described above.

## What is not recorded

- A target using `point` or `item`, a `row` with more than two columns, a `loginAs` that is not the first step:
  the step runs and the result says `notRecorded`. Pick another locator, or add the step to the class by hand.
- A `{"ref":"e12"}` target is recorded as a locator the recorder derives. `fragile:true` means it needed `nth`;
  find a better locator, reset and redo.
- Inside a `batch`, `{"verb":"screenshot","path":"..."}` is the flow step that writes a PNG and IS recorded;
  add `"scale":2` for a HiDPI (2x) image.
  To just look, call the `screenshot` tool.

## Keep it a regression test

- Prefer data-agnostic steps and checks: a row by index (`{"index":0}`), counts with `gte`, values the test typed.
- Drive the controls a user would: the real button, the real menu item. Assert only what the user asked for.
- `navigate`, `close` and `loginAs` need an app plugin; without one they fail with CAPABILITY_GAP. Use `menu`/`press`.

## Export

```sh
$UI export_test '{"class":"OrderSaveUiIT"}'
```

`class` is required and ends in `UiIT`; `method` defaults to `recorded`; `package` to the plugin's export
package, else `recorded`; `clear` to true. The file goes under `-Dswingui.testsDir` of the app JVM, else
`src/test/java` of the plugin's module or of the app's working directory; `savedTo` names it. It refuses a
recording that did not begin at a reset, an empty recording, bad names and an existing file, naming each problem; a
refusal keeps the recording, so fix and retry. Read `warning` (where the file went) and `startWarning` (the test
does not begin by reaching its own screen: reset and re-record with a `menu`/`press` first step).

Review the source. Rename the method if useful. Add by hand only checks no verb expresses, with the Client API
(`ui.describeModal()`, `ui.clipboardText()`, `UiAssertions.eventually(...)`) or the plugin's `ui.hooks(...)`.
Re-record rather than hand-editing recorded steps.

## Verify

1. Put the app on some other screen (another menu item, another tab).
2. Run only the new class while the app is up: `mvn -Dtest=OrderSaveUiIT verify`, or the project's equivalent
   (failsafe picks up `*IT` classes and filters with `-Dit.test=OrderSaveUiIT`).
3. It must pass from there. On a failure read `target/ui-failures/<Class>/<method>/evidence.txt` and the PNG
   beside it; `target/ui-runs/latest.txt` lists the run (`STARTED`, a `PASS`/`FAIL` line per test, `DONE`).
   Usually the start route is at fault: reset and re-record.

Report the file, the steps, the checks, and the verify result.
