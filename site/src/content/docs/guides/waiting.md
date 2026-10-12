---
title: Waiting
description: "Why there are no sleeps: the engine waits for the app to settle."
sidebar:
  order: 2
---

After every action the engine waits until the app has settled. Checks retry until they hold or time out. A click that opens a modal dialog does not hang: it comes back as pending, with the dialog described.

:::note[Being written]
This page is part of the first release of these docs. Until it is written, the [README](https://github.com/kormcrete/swing-ui-agent#readme) covers the basics.
:::
