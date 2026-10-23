# The coverage list

One Markdown file in the plugin module (default `COVERAGE.md`): everything a person can do or see in the app, each
row pointing to what covers it. It is how you, the person and the next survey know the plugin is complete.

## Format

```markdown
# <App> plugin: coverage

Surveyed at: <app commit> (<date>)

## <Screen>

| What the user does or sees | Kind (act/check/start) | Covered by (step id) or Gap (reason) |
|---|---|---|
| <a row in the user's words> | act | `<stepId>` |

## Notes for the steps

- <what an existing step does against rules 5-9 of the skill, and what phase 2 changes>

## Reach fixes needed

| Component | What is missing | Fix | Invisible or visible |
|---|---|---|---|

## Changes made to the app

| Change | For row | Visible | Person's OK |
|---|---|---|---|
```

- **One section per screen**: a window, a tab, a dialog, a wizard. The main window's parts that are always there
  (menu bar, toolbar, status bar) share one section (`## Main window (menus, toolbar, status bar)`). A long wizard
  gets a `###` section per step under its `##`.
- **One row per thing a person does or sees**, in their words: "Add a product to the cart", "The cart total". Name
  the ways to do it in brackets when there are several (menu, toolbar, context menu, key); one step covers them all.
- **One row per kind of value**, not per cell: a table is one row for its lines ("A cart line's quantity, price
  and line total": one check for any line and column), plus one for how many lines and which; a component's
  messages are one row. A question is two rows: its text (a check) and answering it (an act, one per answer that
  leads somewhere different).
- **Kind**: `act` changes the app; `check` reads a value a person sees; `start` is what the reset brings (the start
  screen, a logged-in user).
- **Covered by**: the step's or check's id in backticks, or `reset`, `loginAs`, `navigate` for the app steps. One
  step may cover several rows; a row is covered by one step.
- **`to write`** marks a row a step or check will cover. Once its reach is confirmed on the live app (end of phase
  1) it says `to write: <the calls the step will make>`: a locator, or a `menu` path, a click and its `answer`, a
  `choose_file`, a `describe_ui` field; or its element is in "Reach fixes needed". Rows one parameterised step will
  cover say so (`to write: one check for any line and column`), and may name the step id you propose. No row may
  say `to write` when phase 2 ends.
- **Notes for the steps**: what the survey found in the existing steps (a step that takes the first suggestion by
  chance, fails off its screen only after the full timeout, or presses a button that may be disabled), and later
  the conventions all steps share (how names are matched, `none`, `;` between several values). Phase 2 fixes what
  is wrong and keeps the conventions here.

## Gaps

A row that no step or check will cover is `Gap: <reason>`, with exactly one of these reasons, in these words:

| Reason | When | Example |
|---|---|---|
| `needs app internals even after an app change` | the only way to do or read it is through the app's code, and no small reach fix (a name, a caption) changes that | a value the app computes and never shows |
| `not a tester's concern` | it changes nothing a test proves | the splash while the app starts |
| `the OS owns it` | the operating system draws or runs it, not the app | a native file dialog |

Any other reason is not a gap: the row is `to write`. "Hard to reach" is a reach fix, not a gap. A Swing dialog
that only looks like the system's (a `JFileChooser`) is the app's: the agent drives it with `choose_file`. The
clipboard is no gap either: a check reads it with `ui.clipboardText()`. Before you write `not a tester's concern`,
ask whether a tester would ask for it: Order Desk first gapped its theme switch, a scenario asked to switch the
theme, and it is a step now.

Rows that seem to fit neither, and how they are written:

- **An act that ends the app under test** (Exit): its question and the answer that keeps the app open are rows to
  write; the exit itself is `Gap: not a tester's concern` (no run continues past it).
- **What the app writes outside its screens** (a saved file's text): `Gap: not a tester's concern` when every fact
  in it also shows on a screen (those rows cover it); otherwise ask the person (a Java test may read the file).
- **A message no person can provoke through the UI** (an error that needs data to change behind a modal dialog):
  `Gap: needs app internals even after an app change`; the step that would meet it fails with the app's message
  if it ever shows.

## Reach fixes and app changes

- **Reach fixes needed**: each element phase 1 could not reach honestly: the component (its class or the screen),
  what is missing (no name, no caption, a varying message with no fixed part to find it by, a value only a
  renderer paints, two equal buttons), the fix, and whether a user of the app would see it. Invisible fixes are
  made at the start of phase 2; visible ones wait for the person's OK.
- **Changes made to the app**: each change once it is made: what changed, the row it is for, visible or not, and
  for a visible one who agreed and when. A fix moves from the first list to the second.

## Example: Order Desk (an excerpt)

Taken mid-survey. The covered rows are Order Desk's first 8 steps and checks, and its reset; the `to write` rows
and the gap show the other forms (two `to write` rows still wait for their reach check, and one will be a check
for any line and column).

```markdown
# Order Desk plugin: coverage

Surveyed at: 1be3fe5 (2026-10-22)

## Main window

| What the user does or sees | Kind (act/check/start) | Covered by (step id) or Gap (reason) |
|---|---|---|
| The app starts on the Orders list, no order tab open | start | `reset` |
| Open a new order (File ▸ New order, Ctrl/Cmd+N, toolbar New order) | act | `openNewOrder` |
| Open a new order for a recent customer (toolbar ▾, a customer) | act | `openNewOrder` |
| Close the selected tab (File ▸ Close tab) | act | to write: `menu {"path":["File","Close tab"]}` |
| Switch the theme (View ▸ Theme ▸ Light, Dark, Lagoon) | act | to write: `menu {"path":["View","Theme","<theme>"]}` |
| The splash while the app starts | check | Gap: not a tester's concern |

## Orders list

| What the user does or sees | Kind (act/check/start) | Covered by (step id) or Gap (reason) |
|---|---|---|
| Open a placed order (double click its row) | act | `openOrder` |
| Cancel orders (row menu ▸ Cancel orders…, then confirm) | act | to write: `context_menu` on `{"scope":["tab:Orders"],"role":"table","row":{"Order":"…"}}`, item "Cancel orders…" |

## New order

| What the user does or sees | Kind (act/check/start) | Covered by (step id) or Gap (reason) |
|---|---|---|
| Pick the customer by name or its start (Customer, Enter) | act | `openNewOrder` |
| Add a product to the cart (select it, Add to cart; or its row menu ▸ Add to cart) | act | `addToCart` |
| Add a product with a quantity | act | `addQtyToCart` |
| Change a cart line's quantity (its Qty cell) | act | `setQuantity` |
| Remove a line from the cart (Remove, its row menu ▸ Remove, or Order ▸ Remove from cart) | act | to write |
| The cart total | check | `cartTotalIs` |
| A cart line's quantity, price and line total | check | to write (one check for any line and column) |

## Checkout

| What the user does or sees | Kind (act/check/start) | Covered by (step id) or Gap (reason) |
|---|---|---|
| Check out paid by a PO number, up to the Review step | act | `checkoutWithPo` |
| Place the order and confirm it | act | `placeOrder` |

## Order tab

| What the user does or sees | Kind (act/check/start) | Covered by (step id) or Gap (reason) |
|---|---|---|
| Save the receipt as a text file (Save receipt…, a file chooser) | act | to write |

## Reach fixes needed

| Component | What is missing | Fix | Invisible or visible |
|---|---|---|---|

## Changes made to the app

| Change | For row | Visible | Person's OK |
|---|---|---|---|
```

The steps the example points to, as `vocabulary {"op":"list"}` shows them:

| Id | Sentence |
|---|---|
| `openNewOrder` | Open a new order for {customer} |
| `addToCart` | Add {product} to the cart |
| `setQuantity` | Set the quantity of {product} to {qty} |
| `addQtyToCart` | Add {qty} × {product} to the cart |
| `checkoutWithPo` | Check out with PO number {po} |
| `placeOrder` | Place the order |
| `openOrder` | Open order {order} |
| `cartTotalIs` (check) | The cart total is {total} |
