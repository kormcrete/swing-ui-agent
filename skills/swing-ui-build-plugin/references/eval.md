# The eval: prove the plugin without the source

Two separate agents. A **scenario writer** writes what testers would ask, from what a tester has. A **runner**, a
fresh session with no source, runs every scenario with the plugin as shipped. You count what the runner did from the
app's own timing log. You build the harness for your app yourself: what follows is the method, not a script.

Keep the scenario set and each round's results together (the scenarios, the runner's report, the timing log, the
transcripts, the counts) in a folder version control ignores, unless the person names another place (default:
`.eval/` beside the plugin module, listed in `.gitignore`; one folder per round). Never inside what the runner can
read.

## The scenario writer

**It gets** what a tester has: the app's README (or user guide; when there is none, a few lines the person writes
on what the app is for) and the running app, started **without the plugin**, so it cannot learn the vocabulary
from `vocabulary {"op":"list"}`. It may explore the app freely, snapshots included. **It never gets** the
plugin's source, the app's source or this coverage list. Lock it down like the runner (below), with the README and
the jars in its sandbox and nothing else.

**Ask it for** scenarios spread over every screen and its main actions and values (enough for that: often 15-25),
each one:

- **Request**: exactly as a tester would type it, in a tester's words and loose values ("place an order for Ada
  Shop: 3 packs of A4 paper and 10 black gel pens").
- **Stated checks**: what the tester wants checked, each a value a person can see ("the gel pen line's
  total", "the order shows as Placed"). At least one per scenario.
- **Expected**: the result in the tester's words, or, for a request that cannot be met, the reason the tester
  should get ("there is no customer called Zed").
- **Then**: for some scenarios, "save it as a recorded test and re-run it twice". The re-runs must pass on one
  app start, with no snapshot.

Ask for several requests that cannot be met (an unknown customer, more than is in stock) and a few that touch an
accepted gap (the runner must still do what it can, the slow way, and say the plugin lacked it). The runner does
the scenarios in order on one app start, so a scenario that creates data changes what later ones see (one more
order, one more open order): the Expected values hold for that order, and a saved test checks only what holds on
every run. A stated check computed from another value ("the count goes up by one") is fine as a request: the runner
checks it live and says that a recorded test cannot hold it (a Java test can); it is a known limit, not a miss.

One block per scenario keeps them easy to paste and to count:

```markdown
## S01 Place an order with two products
Request: Place an order for Ada Shop: 3 packs of A4 paper and 10 black gel pens, paid with PO 7781.
Stated checks:
- the cart total before checkout
- the new order shows as Placed
Expected: a new order number, the order Placed.
Then: save as place-order and re-run twice
Corrections:
```

**Before round 1 the person reviews the set** and may edit or add scenarios. Then the set is fixed: later rounds may
add scenarios, never drop or soften a failing one. An expectation is corrected only when the app shows it was wrong;
write the evidence under `Corrections:` and list every correction for the person.

## The runner

**One fresh session for the whole set**, set up as a tester would have it:

- A working directory with no source in it: the agent jar, the app's jar, the **plugin jar as you ship it** (not a
  class folder), the app's bundled recorded tests, and nothing else. No project instructions file (`CLAUDE.md`,
  `AGENTS.md`), no memory from an earlier session (a new directory path per round, automatic memory off).
- swing-ui-agent's skills and driver sub-agent as shipped (in Claude Code the `swing-ui-agent` plugin; a local copy
  loads with `--plugin-dir`; in another coding agent, the same skills and sub-agent as its own instructions say to
  load them), the default model, and the skills deciding whether it drives itself or hands work to the
  `swing-ui-driver` sub-agent.
- The app already running with the plugin jar and the timing log: `-Dswingui.timingLog=<file>` on the app's JVM.
- **Its prompt**: you are a tester; the app runs on port `<p>`; the agent command, written out in full (the
  lock-down allows it only literally: no shell variables, and sub-agents get it the same way); for each scenario
  in order, whoever does its work (the session or a sub-agent) first calls `session '{"op":"info","name":"S01"}'`
  (the marker), then does the request as asked and reports pass or fail per stated check and the result in one
  line; save-and-rerun scenarios save through the skills and re-run twice; **never read files outside your
  working directory**. Paste the scenarios into the prompt: the runner gets no path to where you keep them.

## Keep the source out of reach

The prompt's "never read outside your working directory" is a request. The **lock-down** is what holds, and a
**probe** must prove it closed before a round counts.

- **Tools**: the shell allows only the agent command (permission rules match the command's text, so the prompt gives
  the command literally, without shell variables); file reads only inside the working directory, plus the
  session's own spilled tool output (a long answer such as `vocabulary list` is written to a file the session must
  read back); no search, edit, web or MCP tools that reach files. Sub-agents get the same limits. Permission rules
  alone do not hold: Claude Code, for one, lets read-only shell commands (`ls`, `cat`, `grep`, …) on paths inside
  the working directory through before its permission mode can deny them, and a `$TMPDIR` path slips past
  path rules. What holds is a gate that sees every tool call before it runs (in Claude Code a `PreToolUse` hook in
  the session's settings; sub-agents run it too) and allows one plain agent command: the shell's quotes scanned as
  the shell does (a `'` inside `"…"` is no quote), no `;`, `&`, `|`, `$`, backticks, redirects or brackets outside
  single quotes, the agent's options checked (an `--out` file only inside the working directory). In another
  agent, use its equivalent: whatever runs before each tool call and can refuse it.
- **Operating system**: a sandbox that denies reads of the source tree (and of your home and the temporary
  folders), for the session and for every process it starts, with the working directory allowed back: on macOS a
  `sandbox-exec` profile, on Linux a container or `bwrap` with only the working directory mounted, on Windows a
  separate local user with no rights to the source.
- **The app too**: agent tools that take a path (`run_recorded_test`, an export's folder, `choose_file`) read and
  write files through the app's process. Start the app from the copies in the working directory and confine it the
  same way (a sandbox profile, a container or a separate user). Some look and feels resolve real paths at start
  up, so file metadata may need to stay readable.
- **The probe**: before every round, a session with exactly the runner's setup, minus the "never read" rule, tries
  to read one source file by every route: the read tool, the shell, the agent's path-taking tools, a link outside
  the sandbox that points at the file, any search or MCP tool, a general sub-agent, the driver sub-agent. Every
  route must be denied, and the gate gets its own must-deny / must-allow cases before each round. Keep the rule out
  of the shared setup (put it in the runner's prompt only): with it there, the probe refuses to try and proves
  nothing. A probe that stops before it tried every route proves nothing either: run it again.
- **The audit**: after the round, search the transcripts (the session's and its sub-agents') for any path outside
  the working directory and any line of the plugin's or the app's source.

A round whose probe was not closed, or whose audit found anything, is void: close the hole and run it again.

## What to measure

From the timing log, not from the runner's report: one `UIMCP-TIMING` line per tool call (`tool=`, `status=`,
`ms=`), plus one `UIMCP-STEP` line per step of a batch (do not count those as calls). The marker shows as
`tool=session ... name=S01`; each scenario runs from its marker to the next. Calls before the first marker
(start-up checks, the vocabulary round) count for the session.

Per scenario:

- **Pass or fail per stated check**, from the runner's report, spot-checked against the log and the transcript (did
  the check really run?).
- **Snapshots**: every `snapshot` call, and every snapshot inside another call (a batch's `snapshot` option, a
  `snapshot` step, `"snapshot":true` on a tool). The timing log does not show the ones inside: read them from the
  tool inputs in the transcripts, the sub-agents' included.
- **Screenshots**: every `screenshot` call. One the scenario asked for is its own column, not a driving call.
- **Save and re-run**: `recording`, `export_recorded_test` and `run_recorded_test` calls, in a column of their own;
  and the snapshots from the first re-run on, which must be none.
- **Driving calls**: every other call except `vocabulary`, `session` and `describe_ui`.
- **Errors**: calls whose status is not `ok` (a check guessed on purpose that failed counts; read why).
- **Raw verbs**: driving calls or batch steps that are not `do` steps; each is a plugin gap, written or not.
- **Time**: the sum of the calls' `ms` (time inside the app's agent), and the wall time between markers from the
  transcripts' timestamps.

## The bar

- **All pass**: every scenario does what was asked, every stated check is checked, and a request that cannot be met
  ends with the reason the scenario expects, in the tester's words.
- **No snapshots**: zero snapshots (inside other calls too), and zero `screenshot` calls used to find the way. One
  is a plugin gap.
- **Fast batches**: at most one `vocabulary` list or describe round per session (sub-agents included: a main agent
  that dispatches passes the vocabulary it read to the driver), then at most 2 driving calls per scenario: one
  `batch` for the request, a second only when it needs a value from the first that cannot be linked. Saving and
  re-running, and an asked screenshot, are counted apart.
- **Run and re-run**: saved recorded tests pass twice in a row on one app start, with no snapshot.
- **Time** per scenario is recorded, so later rounds compare.

Each miss is a finding for phase 4: which step or check was missing or unclear, which value did not resolve,
which `about` did not say what the runner needed to chain the steps.
