---
title: Limits
description: What works today, and what does not yet.
sidebar:
  order: 1
---

- **Platforms:** **macOS, Linux (X11) and Windows**, each tested in CI on every change (Linux under a virtual display). On all three the app can be driven while other windows are in front; on macOS, `mode=background` also keeps it out of the Dock. Linux and Windows are tested on GitHub's runners and in virtual machines so far, not yet on many real desktops (GNOME or KDE, Wayland through XWayland, display scaling): reports are welcome. Other systems fail with a clear "capability gap" error rather than misbehaving.
- **Java:** one jar, built for Java 8 so it also attaches to legacy apps still on it. CI tests Java 8, 11, 17, 21 and 25 on macOS, and Java 8 and 25 on Linux and Windows. On Java 9 and newer the app needs no `--add-opens` flags.
- **Toolkits:** Swing only. No JavaFX, no SWT.
- **Distribution:** not on Maven Central yet. Build and install it from source.
