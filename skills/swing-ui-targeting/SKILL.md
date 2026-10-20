---
name: swing-ui-targeting
description: Turns a phrase about a Swing UI ("the Customer field", "Add to cart", "order ORD-1640") into a swing-ui-agent locator and its route. Use whenever a word must become a target to click, type or check.
---

# Targeting the live UI from words

The user names what they see. This skill turns that into a locator the driver can reach, and says what to do when
it cannot. Examples use the CLI as `$UI <tool>`; the same tools exist over MCP at `http://127.0.0.1:<port>/mcp`.

## Your project's part

The project's instructions (its `CLAUDE.md`, `AGENTS.md` or the like) name these; they win over the defaults here.

- **Agent command**: `$UI`, with the agent jar's path and the app's port. Default
  `java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar` on port 8765 (`--port N` before the tool for another one).
- **Glossary**: the file that holds the project's verified targets (below). Default: none; ask the user where
  before you create one.

## The rule

**Actions go through the control a user would use. Values go into the input a user would type in.**
Clicking the real Place order button proves it exists, is enabled and is wired. Reaching the same effect any other
way passes even when the button is broken, which makes the test worthless.

## A locator

`{window, scope, role, name, match, id, row, column, item, point, nth}`; give at least one of `role`, `name`, `id`.

- `window`: `main`, `popup`, `dialog`, `dialog:<title>`, `window:<title>`; a title ending in `*` matches a prefix.
- `scope`: ordered steps the element sits in: `tab:<title>`, `section:<caption>` (a titled border),
  `id:<name>` (strictly inside the component whose `getName()` is `<name>`), `text:<name>` (strictly inside that
  text field). A field's clear X is `{"scope":[...,"text:Search"],"id":"clear"}`: FlatLaf's own X and an app's X
  named `clear` both answer to it. A `tab:` or `section:` step ending
  in `*` matches a title prefix (`tab:New order*` for "New order · Ada Shop Ltd"); this works as a scope step only,
  not as the `name` of the target itself.
- `role`: `window dialog button toggle checkbox radio text password combo list table tree tabs tab menubar menu
  menuitem clickable label section`.
- `name`: component name, own text, caption label, titled border, tooltip or action name. `match:"contains"`
  loosens it. `id`: the component's `getName()`.
- A section: a titled border is `{"role":"section","name":"<caption>"}` (or `role:"section"` with its `id`); a bare
  `{"id":...}` does not match it. A section a plugin contributes on a plain panel without a caption is the other
  way round: bare `{"id":"<section id>"}` only (`describe_ui` `sections` lists the ids).
- Parts inside the element: `row` (`{"Order":"ORD-1640"}` or `{"index":0}`), `column` (a header; with `row`, that
  cell; alone, it also narrows WHICH table: only one with that header), `item` (list/combo item, or a tree path),
  `point` (`{x,y}` fractions, for custom-painted content). A `row` key the table lacks is looked up in its
  row-header table (frozen first columns beside it). Several columns with one header: `"Quantity@<identifier>"`.
- `nth`: which match when not unique. It makes the step `fragile`; treat it as a last resort.
- `{"ref":"e12"}` from the last snapshot works for exploring; it is only valid until the next snapshot that finds
  something (a `{"window":"dialog"}` snapshot with no dialog open keeps them).

## Four levels, one question each

| The user says | Level | Resolve by |
|---|---|---|
| "a new order" | Screen | `menu {"path":["File","New order"]}`, a shortcut via `press`, or `navigate` with a plugin |
| "order ORD-1640", "the first order" | Row | `click` with `count:2` on `{"role":"table","row":{"Order":"ORD-1640"}}`; `{"index":0}` is data-agnostic |
| "the Items tab" | Tab or section | `click` `{"role":"tab","name":"Items"}`; later targets carry `scope:["tab:Items"]` |
| "Customer", "the status filter" | Field | `type` / `expect value` on `{"role":"text","name":"Customer"}` (`combo` for a combo) |
| "Add to cart", "Checkout…" | Button | `click` `{"role":"button","name":"Add to cart"}` |
| "File > New order" | Menu | `menu {"path":["File","New order"]}` |
| "the confirmation" | Dialog | `describe_ui {"fields":["dialog"]}` (plugin) or `snapshot {"window":"dialog"}`; `answer {"button":"Place order"}` |

Work down the levels in order: a field cannot be reached before its tab, a tab before its screen. Most "it didn't
work" is a skipped level. Job-by-job recipes, with the Java each step exports to: `references/recipes.md`.

## Finding the target

- `snapshot` scoped: `{"target":<locator>,"depth":3,"compact":true}` or `{"window":"dialog"}`. Never unscoped:
  it is the whole app. `size.scoped:false` in the answer means scope the next one.
- `read {"target":...}` for one element (a table's columns and rows; `maxRows` limits them).
  `read {"form":"candidates","target":...}` lists what a near-miss locator could mean.
  `read {"form":"menuItems","path":["File"]}` lists a menu without opening it.
- A target that does not resolve fails `NOT_FOUND` or `AMBIGUOUS` with candidates. Read them before guessing.

## Prefer the stable name

- An `id` (`getName()`) beats a display label: it survives translation and rewording. Use the label to find the
  id, then record the id. A snapshot shows both.
- Never match text with data baked in: a window titled `Order ORD-1640` is `dialog:Order *`, not the number.
- A caption label is not a node of its own; it is its field's name. Ask for `{"role":"text","name":"Customer"}`,
  not `{"role":"label",...}`. A label the app made clickable is `role:"clickable"`. One exception: a value label
  is named by its caption. The cart's total, shown next to the caption Total, is `{"role":"label","name":"Total"}`.
- A field with no caption of its own may borrow the nearest label, even an unrelated one. If a `name` looks
  wrong for the field, check it with `read` before relying on it.
- A table's caption (a `setLabelFor` label or titled border) is its `name`. Unnamed tables on one tab are told
  apart by a `column` only one of them has.

## When a word does not resolve

**Look before you call it a gap.** A scoped `snapshot` around where the target should be (depth 3-4) shows what is
really there; most "missing" targets sit under another role, name, tab or window.

**Ambiguity: refuse and say what to add.** A label that names two inputs fails asking for `setLabelFor(field)` in
the app's source. That is the fix to report. Do not silently pick `nth`: a step that hits the wrong widget passes
for the wrong reason, which is worse than a failure.

**A gap is a gap: report it, do not route around it.** A tool error whose `errorType` is `CAPABILITY_GAP` (or an
`INPUT_ERROR` you cannot fix by correcting your own arguments) means the driver cannot do this here. Say so,
verbatim. Do not reach for OS automation (Robot, AppleScript), scripts, the database, `plugin_call` or a raw
component path to get the effect another way: a step recorded off today's layout breaks on the next panel change,
and a shortcut around a control tests nothing.

**Know which failure is whose.** `"passed":false` with `NOT_ACTIONABLE` (disabled, hidden, covered, blocked by a
modal), `TIMEOUT`, `EXPECTATION` or `APP_ERROR` is the app's answer: report what was on screen. A tool error is the
harness. Every call timing out while the app looks alive means its event thread is stuck (often a modal dialog
opened inside `invokeAndWait`): take `jstack <pid>`, read the `AWT-EventQueue-0` thread, and report it.

## Needs an app plugin

Plugins are optional. Without one, everything in this skill works on any Swing app except: `navigate`, `close`
(the active tab), `loginAs` and login by alias, a reset to a defined start state, the app facts of `describe_ui`
(`activeTab`, `navTargets`, `toolbarActions`, `menuPaths`, `viewTabs`, `sections`, `dialog`), and
`plugin_call`/typed hooks. Asked for one of these with no plugin, say it needs a plugin. A plugin may also offer a
vocabulary of the app's own steps (`vocabulary list`; see the swing-ui-record-test skill); it is never needed.

## Behaviours worth knowing

- `type` refuses a disabled or read-only target and says which, so a passing `type` really set the value.
- A step that raises a message or confirmation returns at once with `pending` (title, message, buttons).
  `navigate`, `close` and `loginAs` refuse to run until it is answered; `answer {"button":"escape"}` dismisses it.
- `expect {"selected":[0,1]}` asserts exactly which rows are selected. A table target with a `row` and no
  `column` plus `state:["selected"]` is that row's selection; a cell's `state` is its editability. A ticked check box,
  radio or check menu item has state `checked`; a toggle button and a ticked boolean cell have both `checked` and
  `selected`.
- `scroll` moves the viewport directly (`to`, `pages`, or a `row` on the target, exactly one); read the result from
  the element's `scroll` block or `expect {"state":["atBottom"]}`. Use it, not `press "Ctrl+End"`, for scrolling.
- On the macOS screen menu bar, `menu` invokes the item directly (recorded `via:"api"`).
- `drag` posts a mouse gesture and never starts a native drag; deliver files or text with `drop`.
- `screenshot` paints the app's own components: open popups, combo dropdowns and tooltips (their own windows) are
  not in the image. Use it for alignment and layout calls only; numbers and booleans are `expect`.
- `hover`, `resize_column`, `resize_window` and `close_window` are tools; in a `batch` they are steps like any
  other (`{"verb":"hover","target":...}`). `focusFileChooserName` has no tool: run it through `do_action` or `batch`.
- A step scrolls its target into view through every scroll pane around it: a table row, a list item, a tree row
  or a plain component inside a scrolling form.
- `context_menu` on a table row, list item or tree row selects it first, unless it is already part of the
  selection (a multi-selection stays), as a right-click does in most apps; the menu then acts on it.
- `pending` and `describe_ui`'s `dialog` carry an option pane's whole message, one line per line; for another
  kind of dialog, its first three labels.
- `realInput` in a step result means real mouse or key input reached the app while the step ran (a person at the
  screen), `activations` that the app became the active one: the outcome may be theirs. Say so in your report.

## Keep a glossary

When a project is driven more than once, keep its verified targets (screens and their menu paths, table columns,
button ids, dialog titles) in the project's glossary file, read off the running app, dated. Read it before you
explore. Correct an entry in place
when it turns out wrong; a glossary nobody corrects is worse than none.
