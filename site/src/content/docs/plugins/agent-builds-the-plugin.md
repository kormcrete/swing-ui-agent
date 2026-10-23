---
title: Let your agent build the plugin
description: "How an app developer has a coding agent write a complete plugin for a Swing app, and what testers get from it: plain requests that run as one batch."
sidebar:
  order: 4
---

This page is for **the developer of a Swing app**. You have the source. Your testers do not: they have the built app, its plugin and a coding agent. If the plugin is complete, a tester can say what they want in their own words and their agent does it.

## What your testers get

A tester asks, for example:

> Place an order for Ada Shop Ltd: 3 packs of A4 paper and 10 black gel pens, paid with PO 7781. Check the review total and that the order shows as Placed.

Their agent reads the plugin's [vocabulary](/swing-ui-agent/plugins/vocabulary/) once, picks the steps and checks that fit, and runs the whole request as **one `batch`**. It does not scan the screen to find its way. It answers in the tester's words: the order number, or why it could not ("there is no customer called Zed").

If the tester wants to keep it, the same request can be saved as a **recorded test**: a JSON file that anyone can replay from the palette, in CI or in the IDE. The steps in it read like the request.

## What the agent does for you

You ask your coding agent for the plugin. It works in four phases, and each one has a check before the next starts:

1. **Survey.** It reads the source screen by screen and writes a coverage list: one row per action and per value a person can have or see. It confirms on the running app that each row can be reached.
2. **Write.** It writes a reset, domain steps and checks, with a unit test for each step and check. Where an element cannot be reached honestly (a field with no name, a message whose text varies), it fixes the app in a small way. A name nobody sees it adds at once; anything a user could see it asks you about first.
3. **Prove.** A scenario writer that has no source writes what testers would ask. A fresh agent with no source, only the app and the plugin, runs them all.
4. **Fix.** It fixes what the run showed, in the plugin, the app or the list, and runs again. After three rounds without meeting the bar it stops and reports.

A step acts through the same controls a person uses and reads what a person sees. It never calls the app's own classes, so a step cannot pass while the button it stands for is broken.

## The bar

The plugin is done when the coverage list is complete **and** a fresh agent without the source passes all scenarios with:

- no `snapshot` (also none inside a batch) and no screenshot to find the way;
- one `vocabulary` call per session;
- at most 2 driving calls per scenario.

Order Desk's plugin was built this way. In the last round it met the bar in 22 of 25 scenarios, with 0 snapshots (the one screenshot a scenario asks for is not counted as a driving call). One scenario asks for a computed value ("one more than before"), which a recorded test cannot hold; it stays a Java test. Two missed because of the tester agent's habits, not because of a missing step. See the [steps of Order Desk's plugin](/swing-ui-agent/plugins/vocabulary/#order-desks-vocabulary): 98 of them.

## How to start

Install the swing-ui-agent skills for your coding agent (see [Claude Code](/swing-ui-agent/start/claude-code/)). Then ask:

> Build the plugin for this app with the swing-ui-build-plugin skill.

It asks where to put the plugin (a Maven project of its own beside the app is the default) and how to build and start your app. Then it works. It asks you first:

- before it creates the plugin project;
- before any change to the app that a user could see;
- before it fixes a bug it found in the app (it tells you what it found);
- to review the scenarios before the first round.

You see the final result: the coverage list and the eval round that met the bar.

## When the app changes

Ask the same question again: "update the plugin for what changed". The agent runs the same loop on the change. A plugin that no longer matches the app, without anyone noticing, is a failure.

## What stays Java

A value computed from other values, a loop, a branch: a recorded test holds none of these. They go in a Java test that calls the same steps as typed methods.
