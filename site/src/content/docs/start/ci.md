---
title: Run in CI
description: Start the app with the agent on a CI runner, wait until it is ready, run the tests.
sidebar:
  order: 6
---

UI tests run on macOS, Linux and Windows runners. macOS and Windows runners have a desktop session; on Linux, start the app and the tests under a virtual display, for example `xvfb-run -a mvn verify`. The project's own [CI workflow](https://github.com/kormcrete/swing-ui-agent/blob/main/.github/workflows/build.yml) does this on all three; see also [Limits](/swing-ui-agent/project/limits/).

:::note[Being written]
This page is part of the first release of these docs. Until it is written, the [README](https://github.com/kormcrete/swing-ui-agent#readme) covers the basics.
:::
