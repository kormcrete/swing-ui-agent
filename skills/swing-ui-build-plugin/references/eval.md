# The eval: prove the plugin without the source

Two separate agents. A **scenario writer** writes what testers would ask, from what a tester has. A **runner**, a
fresh session with no source, runs every scenario with the plugin as shipped. You count what the runner did from the
app's own timing log. You build the harness for your app yourself: what follows is the method, not a script.

Keep the scenario set and each round's results together (the scenarios, the runner's report, the timing log, the
transcripts, the counts), where the person keeps such notes. Never inside what the runner can read.

## The scenario writer

**It gets** what a tester has: the app's README (or user guide) and the running app, started **without the plugin**,
so it cannot learn the vocabulary from `vocabulary {"op":"list"}`. It may explore the app freely, snapshots
included. **It never gets** the plugin's source, the app's source or this coverage list. Lock it down like the
runner (below), with the README and the jars in its sandbox and nothing else.

**Ask it for** scenarios spread over every screen and its main actions and values (enough for that: often 15-25),
each one:

- **Request**: exactly as a tester would type it, in a tester's words and loose values ("place an order for Ada
  Shop: 2 desk lamps and a notebook").
- **Stated checks**: what the tester wants checked, each a value a person can see ("the desk lamp line's
  total", "the order shows as Placed"). At least one per scenario.
- **Expected**: the result in the tester's words, or, for a request that cannot be met, the reason the tester
  should get ("there is no customer called Zed").
- **Then**: for some scenarios, "save it as a recorded test and re-run it twice". The re-runs must pass on one
  app start, with no snapshot.

Ask for several requests that cannot be met (an unknown customer, more than is in stock) and a few that touch an
accepted gap (a tester may well ask to switch the theme; the runner must still do it, the slow way, and say the
plugin lacked it).

One block per scenario keeps them easy to paste and to count:

```markdown
## S01 Place an order with two products
Request: Place an order for Ada Shop: 2 desk lamps and a notebook, paid with PO 7781.
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
  loads with `--plugin-dir`), the default model, and the skills deciding whether it drives itself or hands work to
  the `swing-ui-driver` sub-agent.
- The app already running with the plugin jar and the timing log: `-Dswingui.timingLog=<file>` on the app's JVM.
- **Its prompt**: you are a tester; the app runs on port `<p>`; the agent command, written out in full; for each
  scenario in order, first `session '{"op":"info","name":"S01"}'` (the marker), then do the request as asked and
  report pass or fail per stated check and the result in one line; save-and-rerun scenarios save through the
  skills and re-run twice; **never read files outside your working directory**. Paste the scenarios into the
  prompt: the runner gets no path to where you keep them.

## Keep the source out of reach

The prompt's "never read outside your working directory" is a request. The **lock-down** is what holds, and a
**probe** must prove it closed before a round counts.

- **Tools**: the shell allows only the agent command (permission rules match the command's text, so the prompt gives
  the command literally, without shell variables); file reads only inside the working directory; no search, edit,
  web or MCP tools that reach files. Sub-agents get the same limits.
- **Operating system**: a sandbox that denies reads of the source tree (and of your home), for the session and for
  every process it starts.
- **The app too**: agent tools that take a path (`run_recorded_test`, an export's folder, `choose_file`) read and
  write files through the app's process. Start the app from the copies in the working directory and confine it the
  same way (a sandbox profile, a container or a separate user).
- **The probe**: before every round, a session with exactly the runner's setup, minus the "never read" rule, tries
  to read one source file by every route: the read tool, the shell, the agent's path-taking tools, a link outside
  the sandbox that points at the file, any search or MCP tool, a general sub-agent, the driver sub-agent. Every
  route must be denied. Keep the rule out of the shared setup (put it in the runner's prompt only): with it there,
  the probe refuses to try and proves nothing.
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
- **Snapshots**: every `snapshot` call.
- **Screenshots**: every `screenshot` call, unless the scenario asked for a picture.
- **Driving calls**: every call except `vocabulary`, `session` and `describe_ui`.
- **Errors**: calls whose status is not `ok`.
- **Time**: the sum of the calls' `ms` (time inside the app's agent), and the session's wall time.

## The bar

- **All pass**: every scenario does what was asked, every stated check is checked, and a request that cannot be met
  ends with the reason the scenario expects, in the tester's words.
- **No snapshots**: zero `snapshot` calls, and zero `screenshot` calls used to find the way. One is a plugin gap.
- **Fast batches**: at most one `vocabulary` list or describe round per session (sub-agents included: a main agent
  that dispatches passes the vocabulary it read to the driver), then at most 2 driving calls per scenario: one
  `batch` for the request, a second only when it needs a value from the first that cannot be linked.
- **Run and re-run**: saved recorded tests pass twice in a row on one app start, with no snapshot.
- **Time** per scenario is recorded, so later rounds compare.

Each miss is a finding for phase 4: which step or check was missing or unclear, which value did not resolve,
which `about` did not say what the runner needed to chain the steps.
