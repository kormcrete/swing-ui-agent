---
title: Leases and several clients
description: One owner drives the app at a time; how tests, the CLI and a person at the app share it.
sidebar:
  order: 9
---

One app has one mouse, one keyboard focus and one set of open dialogs. If a test run and an agent's CLI call acted at the same time, each would break the other. So the agent lets one owner drive at a time, through a lease. This page shows how the lease works, how tests and a person at the app take turns, and how one test drives several apps.

The examples run from the Order Desk folder, with `UI="java -jar target/swing-ui-agent-agent.jar"`.

## Reads are free, actions take turns

- **A read** (`snapshot`, `read`, `screenshot`, `read_clipboard`, `read_file_chooser`, `session` info) needs no lease and runs at any time.
- **An action** (every step that clicks, types, presses, answers, scrolls, or resets) runs only for the lease holder, or, when nobody holds the lease, for one call without one.

So a CLI call with no lease is its own one-call lease: it runs when the app is free and is refused while someone else holds it:

```json
{"errorType":"INPUT_ERROR","reason":"BUSY","message":"BUSY: leased by docs-walkthrough since 12:35:04"}
```

The CLI exits with code 1. `session` shows who holds the lease, under `lease`.

## Take a lease

For a run of steps nothing should interrupt, take the lease with `session`, pass its token on each call, and release it at the end:

```sh
$UI session '{"op":"lease","owner":"docs-walkthrough","ttlMs":60000}'
```

```json
{"token":"27d76906f90a8a818eb6de54","owner":"docs-walkthrough","since":"2026-10-13T10:35:04.030Z","ttlMs":60000}
```

```sh
$UI --lease 27d76906f90a8a818eb6de54 click '{"target":{"role":"tab","name":"Orders"}}'
$UI session '{"op":"release","token":"27d76906f90a8a818eb6de54"}'
```

Over MCP the token goes in each call's `_lease` argument.

- **It ends** on `release`, after `ttlMs` without a call (default 120 seconds), or when another owner takes over.
- **A takeover** happens only when the holder has been idle for 15 seconds with no call running. A test JVM that died mid-test does not lock the app for the whole two minutes.
- **A call that runs long** keeps the lease alive however long it takes. `{"op":"renew","token":"…"}` keeps it alive while the holder makes no call; a read that carries the token renews it too.

## Tests take a lease per test

You never handle the lease in a test. Before each `@UiTest` method the extension:

1. leases the app, as owner `<TestClass>#<method>`;
2. waits for a busy app up to 16 seconds, retrying every half second (`-Dswingui.leaseWaitMs` changes it), then fails with the holder's name;
3. resets it (see [Reset and the starting screen](/swing-ui-agent/guides/reset/));
4. keeps the lease alive with a heartbeat every 5 seconds, even while the test waits on something else;
5. releases it after the test, whatever happened, and on JVM shutdown.

While a test runs, the agent's own CLI calls are refused with `BUSY: leased by PlaceOrderUiIT#placesAnOrderForAda …`. That is the agent's cue to wait, not to take the lease from a run that is still going.

## A person at the app

With the palette on (the default), a person at the screen can record steps and answer the agent's questions in the app (see [Record a test by showing it](/swing-ui-agent/start/show/)). The person and test runs take turns too:

- **While the person records, picks an element, answers a question or replays steps,** no test run can take the lease. The test waits as above and then fails with what the person is doing:

```text
BUSY: a person is recording in the app (they end it with Done in its palette)
```

- **While a test run holds the lease,** the palette leaves the window, so the person cannot start anything; it comes back when the lease ends.
- **`session reset` is refused** while the person records: a reset starts a fresh recording and would wipe theirs.

Start with `palette=off` on machines that run tests unattended.

## Several apps in one test

Some behavior needs two users at once: two clerks ordering the last stapler in stock, say. A test gets one client per `Client` parameter, each leased on its own app:

```java
@UiTest
class LastStaplerUiIT {

    @Test
    void onlyOneClerkGetsTheLastStapler(Client first, Client second) {
        // bring both checkouts to the Place order confirmation step by step, then answer both at once
        UiParallel.run(
                () -> first.answer("Place order"),
                () -> second.answer("Place order"));
    }
}
```

- **Which apps:** the JUnit configuration parameter `swingui.agents` lists them as `host:port,host:port` (a system property, `junit-platform.properties`, or a suite's `@ConfigurationParameter`). The default is the one agent on `swingui.port`.
- **Taking turns:** a test reserves all the apps it needs at once, lowest free ports first, and waits for other tests in its JVM to give them back (`-Dswingui.poolWaitMs`, default 10 minutes).
- **Logins:** with a plugin, `@As("alias")` on a parameter logs that client in as another user.
- **`UiParallel.run`** releases one final action per client at the same moment, each on its own thread. Which one wins is not under the test's control, so assert what must hold either way: exactly one order gets the stapler, and the stock never goes below zero.

Order Desk keeps its data in memory, so two Order Desk apps share nothing and this example is a sketch; the pattern is for apps whose clients share a server.

## What's next

- [Run the tests](/swing-ui-agent/start/run/): run a test class against the running app.
- [Security](/swing-ui-agent/agents/security/): who can reach the agent.
