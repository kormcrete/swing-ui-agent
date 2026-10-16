---
title: Run in CI
description: Start the app with the agent on a CI runner, wait until it answers, run the recorded tests (and any Java tests) and keep the screenshots and failure evidence.
sidebar:
  order: 12
---

In CI the job does what you do by hand: start the app with the agent, wait until the agent answers, run the recorded tests (and any Java tests), and keep the screenshots and the failure evidence. This page follows Order Desk's own GitHub Actions job, which runs on Linux under a virtual display.

## 1. A script that starts the app and runs the tests

The tests are [recorded tests](/swing-ui-agent/start/recorded-tests/#in-maven-and-ci), replayed by the small `RecordedUiIT` class, plus Order Desk's Java tests. Both are named `*UiIT`, so one failsafe run covers them and nothing else changes. Keep `palette=off` in the script, as shown: nobody is at the screen to use the palette. Keep `target/failsafe-reports/` (the job below already does): a failing recorded test leaves its report and a picture of the failing step in `recorded-test-failures/` there.

Order Desk keeps the steps in one script, so CI and a person run them the same way. On Linux without a display it starts Xvfb first and waits for its socket:

<!-- snippet: .github/scripts/ui-tests.sh#L8-L37 -->
```sh
PORT="${SWINGUI_PORT:-8770}"
AGENT=target/swing-ui-agent-agent.jar
PLUGIN=order-desk-plugin/target/classes   # Order Desk's domain steps, for the recorded tests' do steps
APP=

if [ "${1:-}" != --attach ]; then
  if [ "$(uname)" = Linux ] && [ -z "${DISPLAY:-}" ]; then
    Xvfb :99 -screen 0 1600x1000x24 > target/xvfb.log 2>&1 &
    XVFB=$!
    export DISPLAY=:99
    for i in $(seq 1 50); do [ -e /tmp/.X11-unix/X99 ] && break; sleep 0.2; done   # the app needs the display up
    if [ ! -e /tmp/.X11-unix/X99 ]; then echo "Xvfb did not start:"; cat target/xvfb.log; exit 1; fi
  fi
  java -javaagent:"$AGENT"=port="$PORT",palette=off,plugins="$PLUGIN" -Dorderdesk.today=2026-10-12 -jar target/order-desk.jar > target/app.log 2>&1 &
  APP=$!
  trap 'kill "$APP" 2>/dev/null || true; if [ -n "${XVFB:-}" ]; then kill "$XVFB" 2>/dev/null || true; fi' EXIT
fi

for i in $(seq 1 60); do
  if java -jar "$AGENT" --port "$PORT" session > /dev/null 2>&1; then echo "Order Desk is ready (${i}s)"; break; fi
  if [ -n "$APP" ] && ! kill -0 "$APP" 2>/dev/null; then echo "Order Desk stopped:"; cat target/app.log; exit 1; fi
  if [ "$i" = 60 ]; then
    echo "Order Desk did not answer on port $PORT in 60 s"
    if [ -n "$APP" ]; then cat target/app.log; fi
    exit 1
  fi
  sleep 1
done

mvn -B -Pui -Dswingui.agents="127.0.0.1:$PORT" failsafe:integration-test failsafe:verify
```

- **Wait for `session`.** It exits `0` once the agent answers, and `3` while nothing listens yet.
- **Stop early when the app dies,** and print its log: a missing display or a bad flag shows up there.
- **`palette=off`** leaves the recording palette out: nothing in the app listens to input, and a test run's lease would hide it anyway.
- **`plugins=`** loads Order Desk's [vocabulary plugin](/swing-ui-agent/plugins/vocabulary/), built and installed by the job's step before it: the recorded test `cart-with-steps.json` holds `do` steps that only the plugin can run.
- **`--attach`** starts nothing and tests an Order Desk that already runs with the agent, such as a download.
- **The `trap`** stops the app (and Xvfb) when the script ends, whatever the result.
- **The failsafe goals run alone,** because the build before it already compiled the tests.
- **`-Dswingui.agents`** points the tests at the port the script used, over the one in `junit-platform.properties`.

## 2. The job

<!-- snippet: .github/workflows/build.yml#L29-L57 -->
```yaml
- name: Install swing-ui-agent
  run: mvn -B -f swing-ui-agent/pom.xml install -DskipTests
- name: Build and install the Order Desk plugin (its domain steps; the app's UI tests use it)
  run: mvn -B -f order-desk-plugin/pom.xml install
- name: Build and test
  run: mvn -B verify
- name: UI tests (Order Desk with the agent, on a virtual display)
  run: .github/scripts/ui-tests.sh
- name: Keep test reports
  if: always()
  uses: actions/upload-artifact@v7
  with:
    name: test-reports
    path: |
      target/surefire-reports/
      target/failsafe-reports/
      order-desk-plugin/target/surefire-reports/
    if-no-files-found: ignore
- name: Keep UI screenshots
  if: always()
  uses: actions/upload-artifact@v7
  with:
    name: ui-screenshots
    path: |
      target/ui-screenshots/
      target/ui-failures/
      target/failsafe-reports/
      target/app.log
    if-no-files-found: ignore
```

- swing-ui-agent is not on Maven Central yet, so the job checks out its repository and installs it first.
- `mvn -B verify` runs the unit tests without the app. The UI tests run next, against the app.
- `if: always()` keeps the screenshots and the evidence when a test fails, which is when you need them.

The `ui-screenshots` artifact of a green run holds the ten 2x screenshots of the Java test `PlaceOrderUiIT`; they are shown in [PlaceOrderUiIT, step by step](/swing-ui-agent/demo/recorded-test/).

## On each runner

- **Linux:** start a virtual display (Xvfb) when there is none, as above. Fonts differ from macOS and Windows, so labels can be wider; look at the screenshots of the first run.
- **macOS and Windows:** GitHub's runners have a desktop session; start the app as it is. swing-ui-agent's own [CI workflow](https://github.com/kormcrete/swing-ui-agent/blob/main/.github/workflows/build.yml) starts a test app with the agent and drives it on macOS, Linux and Windows runners on every change. Order Desk's script is written for a Unix shell; on Windows, do the same steps in your own.

[Limits](/swing-ui-agent/project/limits/) lists the desktops tested so far.

## What's next

- [Screenshots](/swing-ui-agent/guides/screenshots/).
- [Leases and several clients](/swing-ui-agent/guides/leases/): several test JVMs, or a person and a test run, on one app.
