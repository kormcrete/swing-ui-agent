---
title: Run the tests
description: Run the exported tests from Maven or IntelliJ against the running app.
sidebar:
  order: 5
---

Exported tests are plain JUnit 5 classes. They connect to the agent on `127.0.0.1:8765` (`-Dswingui.port` changes it) while the app is running. The verdict lands in `target/ui-runs`, and each failure leaves its evidence in `target/ui-failures`.

:::note[Being written]
This page is part of the first release of these docs. Until it is written, the [README](https://github.com/kormcrete/swing-ui-agent#readme) covers the basics.
:::
