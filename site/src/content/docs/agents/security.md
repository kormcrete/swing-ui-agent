---
title: Security
description: What the agent can and cannot do, and where to run it.
sidebar:
  order: 3
---

The agent gives whoever can reach its port the same power over your app as a person at the keyboard. This page says who can reach it, what they can do, and where to run it.

## Who can reach it

- **This machine only.** The agent listens on a loopback address: `127.0.0.1` by default, or `::1` or `localhost`. It refuses any other `bind` value at startup and starts nothing.
- **No web pages.** A request carrying an `Origin` header from anything other than localhost is refused (HTTP 403). So is a body that is not `application/json` (HTTP 415), which a browser cannot send to another site without a preflight that the agent never approves. A page open in your browser cannot drive the app.
- **One endpoint.** `/mcp` on the one port, POST only. Its threads are daemons, so it never keeps the app's JVM alive.

## What a caller can do

- **What a user can do, through the UI.** Click, type, press keys, pick from menus, answer dialogs, choose files in the app's file chooser, read what is on screen, take screenshots of the app's windows.
- **Write files, in two places.** `export_test` writes a test class under the tests folder (`-Dswingui.testsDir`, else `src/test/java`), and refuses to overwrite an existing file. Screenshot steps write PNGs under the screenshot folder or a path the caller gives. Exported tests are code: review them like any other change.
- **Read the clipboard, and set it.** `read_clipboard` and `set_clipboard` work with the system clipboard as the app sees it.
- **Call a plugin's hooks.** `plugin_call` reaches only the methods of the one interface a plugin declares as its hooks. Without a plugin there is nothing to call.

## What a caller cannot do

- **Run commands or evaluate code.** No tool starts a process, loads a class or evaluates an expression.
- **Read a password.** A password field's content is never put into a snapshot, never read and never checked. A person typing into one while recording with the palette is not recorded either.
- **See your credentials.** Login aliases and secrets live in `.swing-ui-agent/ui-users.properties` on the machine that runs the tests. Error messages list alias names, never their passwords or secrets. Keep the file out of version control.
- **Take your mouse.** Input is posted into the app's own event queue. The real pointer and keyboard stay yours, and the app can be driven while other windows are in front.

## One owner at a time

A test run, a script or an agent can take a **lease**: then only calls that carry its token can act, and everyone else can only read. A lease ends when it is released, after its time-to-live passes without a call, or when another owner takes over an agent that has been idle for 15 seconds. While a person records, picks or replays with the palette, no test run can take the lease. See [Leases and several clients](/swing-ui-agent/guides/leases/).

## Where to run it

- **Development and test machines, and CI.** Start the app with the agent only where you mean to drive it.
- **Test data.** The agent clicks what it is told to click. Point the app at a test database or a test account, never at production data.
- **The person's choice.** The palette (`inspector=on`) is for a person at the screen. Leave it off on CI and shared machines: then no palette appears and nothing listens to input.

## What's next

- [Agent options](/swing-ui-agent/reference/agent-options/): `bind`, `port`, `inspector`.
- [Limits](/swing-ui-agent/project/limits/)
