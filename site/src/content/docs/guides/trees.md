---
title: Trees
description: Read a tree, click a row by its path, expand and check it.
sidebar:
  order: 8
---

A tree is read and targeted by paths: the labels from the top down to the row you mean. Order Desk's New order tab has one, the product categories on the left. This page reads it, selects a category, and checks the result.

The examples run from the Order Desk folder, with `UI="java -jar target/swing-ui-agent-agent.jar"`, on a New order tab (`menu {"path":["File","New order"]}`).

## Read a tree

`read` lists the visible rows as paths, joined with ` > `, and which ones are expanded:

```sh
$UI read '{"target":{"scope":["tab:New order"],"role":"tree"}}'
```

```json
{"found":true,"node":{"role":"tree","state":["atTop","atBottom"],"rowCount":9,"selected":[0],
 "items":["All products","Office","Office > Paper","Office > Pens","Office > Desk","Coffee","Coffee > Beans","Coffee > Machines","Cleaning"],
 "expanded":["Office","Coffee"]}}
```

`rowCount` and `selected` count visible rows, top to bottom. A row under a collapsed parent is not listed and cannot be targeted until the parent is expanded.

## Click a row

A row is the tree target plus an `item`, its path as a list of labels:

```sh
$UI click '{"target":{"scope":["tab:New order"],"role":"tree","item":["Office","Pens"]}}'
$UI read '{"target":{"scope":["tab:New order"],"role":"table","column":"Stock"}}'
```

The products table now shows the four pens. A double click (`"count":2`) does what it does for a user: on a parent row, it collapses or expands it.

```sh
$UI click '{"target":{"scope":["tab:New order"],"role":"tree","item":["Office"]},"count":2}'
```

After that, the read shows `"expanded":["Coffee"]` and Office's children are gone from `items`. A second double click brings them back. With the row selected, `press {"keys":"Right"}` and `"Left"` expand and collapse it too.

## Check a row

A tree row has three states: `selected`, `expanded` and `leaf`.

```sh
$UI expect '{"target":{"scope":["tab:New order"],"role":"tree","item":["Office","Pens"]},"state":["selected","leaf"]}'
```

The tree as a whole takes the same checks as a table for its rows: `"rows":{"op":"eq","value":9}` and `"selected":[3]`.

## In a test

A step aimed at a tree row is exported like any other, as `item` in the Java test:

```java
ui.tab("New order").tree(null).item("Office", "Pens").click();

assertThat(ui.tab("New order").tree(null).item("Office", "Pens")).isSelected();
```

`tree(null)` is the tab's only tree; pass the tree's name when it has one. A list entry (a `JList` row, not a tree row) is chosen with `select` and its text: `select {"target":{"role":"list","name":"…"},"item":"…"}`, in Java `ui.list("…").select("…")`. A click on an entry of a suggestion popup is exported as `ui.popup().list(null).item("…").click()`.

When a person records with the palette, a click on a tree row or a list entry, or a pick from a suggestion popup, is recorded the same way, as a `click` with `item`. Double-click a row to expand or collapse it while recording: a click on the expand arrow is not recorded, and the palette says so.

## What's next

- [Tables](/swing-ui-agent/guides/tables/): rows by their values.
- [Limits](/swing-ui-agent/project/limits/): what the driver cannot do yet.
