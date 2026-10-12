---
title: Limits
description: What works today, and what does not yet.
sidebar:
  order: 1
---

- **Platforms:** the driver runs on **macOS**. On Linux and Windows it fails with a clear "capability gap" error rather than misbehaving. Both are in progress.
- **Java:** one jar, built for Java 8, tested on Java 8, 11, 17, 21 and 25. On Java 9 and newer the app needs no `--add-opens` flags.
- **Toolkits:** Swing only. No JavaFX, no SWT.
- **Distribution:** not on Maven Central yet. Build and install it from source.
