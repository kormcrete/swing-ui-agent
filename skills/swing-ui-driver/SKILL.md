---
name: swing-ui-driver
description: Drives a live Swing app through swing-ui-agent the way a user would - walking screens, checking a fix by hand, running a recorded UI test, capturing screens - and reports PASS/FAIL/GAP per item. Use it to hand UI driving to a cheaper sub-agent instead of driving from the main session; the body also works as that sub-agent's definition.
---

# Drive the live app, cheaply

Driving a UI is many small, round-trip-bound calls. Hand it to a smaller, cheaper sub-agent and keep the main
session for decisions. This file serves both sides: **Delegating** is for the dispatcher; everything after it is
the driver's instructions, and can be used as-is as a sub-agent definition (add your platform's frontmatter, e.g.
a smaller model and the shell tool).

## Delegating (dispatcher)

Delegate when the job is "go and look" or "go and do": walk a flow, confirm a fix in the real app, reproduce a
bug, run or re-run a `*UiIT`, capture screens. Keep in the main session: deciding what a test should prove, and
recording a test the user is shaping step by step (swing-ui-record-test).

Hand the driver:
- **What to walk**, as a checklist of items each with an observable outcome ("Orders > New order opens a dialog
  titled New order"; "Save stays disabled until Customer is filled").
- **The known targets**, if any (locators, menu paths, ids), so it does not rediscover them.
- **Whether to record and export**, and the class name. Default: no.
- **Where to write results**, and the port if not 8765.
- **What it must not touch** (other windows, real data, a dialog that sends something).

Expect back at most a few lines: verdict, counts (passed / failed / gaps), the results file, any blocker.

## First (driver)

1. Load the swing-ui-targeting skill: it turns the dispatch's words into locators and lists what needs a plugin.
2. Every tool is one shell call; the same tools exist over MCP at `http://127.0.0.1:8765/mcp`:
   ```sh
   UI="java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar"   # add --port N before the tool if not 8765
   $UI session          # up? plugins ready?
   $UI tools            # list; `$UI tools batch` for one schema
   ```
   Exit 0 ok, 1 tool error (stderr), 2 usage, 3 no agent reachable. On exit 3, stop and say so. Do not start,
   stop or restart the app unless the dispatch says to.
3. A step that ran and failed exits 0 with `"passed":false`. Always read `passed`.

## Driving rules

- **Scope every read.** Never an unscoped `snapshot`: it is the whole app and costs thousands of tokens. Use
  `snapshot {"target":<locator>,"depth":2-4,"compact":true}` or `{"window":"dialog"}`, `read {"target":...}` for
  one element, `expect` for a yes/no. With a plugin, `describe_ui {"fields":["dialog"]}` names only what you need.
  If an answer says `size.scoped:false`, scope the next one.
- **Batch what you already know.** Consecutive steps that do not depend on what you are about to read go in ONE
  `batch` call; it stops at the first step that does not pass and reports each step. Add `"snapshot":{...}` to get
  a scoped look at the end in the same call. Make a single call only when the next step depends on the last read.
  ```sh
  $UI batch '{"steps":[
    {"verb":"menu","path":["Orders","New order"]},
    {"verb":"type","target":{"role":"text","name":"Customer"},"text":"Acme"},
    {"verb":"expect","target":{"role":"button","name":"Save"},"state":["!disabled"]}],
    "snapshot":{"window":"dialog","depth":3,"compact":true}}'
  ```
  `do_actions {"actions":[...]}` does the same with flow steps; in `do_action` the step sits one level down:
  `{"action":{"action":"click","target":{...}}}`.
- **Check with `expect`, not screenshots.** A number or a boolean is an `expect`. Take a `screenshot` (with
  `--out file.png`) only for a visual call or when the dispatch asks.
- **Report, don't repair.** `"passed":false` is the APP's answer: report the step, `reason`, `message` and what
  was on screen. A tool error with `errorType` `CAPABILITY_GAP`, or an `INPUT_ERROR` you cannot fix by correcting
  your own arguments, is a HARNESS gap: report it verbatim as GAP.
- **Never route around a failure or a gap.** No OS automation (Robot, AppleScript), no scripts, no database, no
  `plugin_call` to fake a step, no `nth` to force a match, no raw component paths. A gap is a result.
- **Answer dialogs deliberately.** A result with `dialog` means a message waits; answer it only as the dispatch
  intends (`answer {"button":"Cancel"}` or `"escape"`), never by clicking whatever is offered.
- **No `export_test` unless asked.** Steps are recorded either way; exporting unasked leaves a Java file behind.
  If asked to record, follow swing-ui-record-test (start at a `session` reset, first step reaches the start screen).
- **Busy agent.** `busy: leased by <owner> since ...` means a test run holds the app. Never take the lease from a
  run still going. If that run is dead, retry after 15 s idle, or `$UI session '{"op":"lease","owner":"driver"}'`,
  pass `--lease <token>` before the tool name on each call, and release it at the end.
- **Plugin-only steps.** `navigate`, `close`, `loginAs`, `describe_ui` app facts and resets to a start state need
  an app plugin. Without one, reach screens with `menu` or `press`; a missing plugin is a GAP, not a workaround.

## Running a recorded test

Run the given `*UiIT` class alone while the app is up, with the project's test command (e.g.
`mvn -Dtest=OrderSaveUiIT verify`; failsafe uses `-Dit.test=`). Read `target/ui-runs/latest.txt` (`STARTED`, a
`PASS`/`FAIL` line per test, `DONE`) and, on a failure, `target/ui-failures/<Class>/<method>/evidence.txt` with the
PNG beside it. Fix the Java only if the dispatch asks; otherwise report the failing step and its evidence path.

## Finishing

Write the results to the file the dispatch names: one line per item (`PASS` / `FAIL` / `GAP` and the item), then
each failure with its message and what was on screen, each gap verbatim, and any screenshot paths. Reply in at
most 8 lines: verdict, counts, results file, blockers.
