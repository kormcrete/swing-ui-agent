---
name: swing-ui-driver
description: Hands UI driving of a live Swing app (walk screens, check a fix, run a recorded or Java UI test, screenshots) to the cheaper swing-ui-driver sub-agent, which reports PASS/FAIL/GAP. Use before driving the app from the main session.
---

# Drive the live app, cheaply

Driving a UI is many small, round-trip-bound calls. Hand it to a smaller, cheaper sub-agent and keep the main
session for decisions. The sub-agent is `swing-ui-driver`: in Claude Code the swing-ui-agent plugin ships it
(`swing-ui-agent:swing-ui-driver`); elsewhere use `agents/swing-ui-driver.md` from the swing-ui-agent repository
as the sub-agent's definition and adapt its frontmatter (a smaller model, the shell tool).

## Delegating

Delegate when the job is "go and look" or "go and do": walk a task, confirm a fix in the real app, reproduce a
bug, run or re-run a `*UiIT`, capture screens. Keep in the main session: deciding what a test should prove, and
recording a test the user is shaping step by step (swing-ui-record-test).

Hand the driver:
- **What to walk**, as a checklist of items each with an observable outcome ("File > New order opens a New order
  tab"; "Checkout… stays disabled while a cart line asks for more than is in stock").
- **The known targets**, if any (locators, menu paths, ids), so it does not rediscover them.
- **Whether to record and export**, and the class name. Default: no.
- **Where to write results.**
- **The project's part**, when the driver cannot read the project's instructions: the agent command and port, how
  tests run, how to start the app, which data it may change, the glossary file.
- **What it must not touch** (other windows, real data, a dialog that sends something).
- **How many passes** over the checklist at most (e.g. 2). A driver without a limit can loop for a long time on
  an item it cannot get through.

Expect back at most a few lines: verdict, counts (passed / failed / gaps), the results file, any blocker.

