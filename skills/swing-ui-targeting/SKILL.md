---
name: swing-ui-targeting
description: Turns a phrase about a Swing UI ("the Customer field", "Add to cart", "order ORD-1640") into a swing-ui-agent locator and its route. Use whenever a word must become a target to click, type or check.
---

# Targeting the live UI from words

The user names what they see. This skill turns that into a locator the driver can reach, and says what to do when
it cannot. Examples use the CLI (`UI="java -jar swing-ui-agent-0.1.0-SNAPSHOT-agent.jar"`); the same tools exist
over MCP at `http://127.0.0.1:8765/mcp`.

## The rule

**Actions go through the control a user would use. Values go into the input a user would type in.**
Clicking the real Place order button proves it exists, is enabled and is wired. Reaching the same effect any other
way passes even when the button is broken, which makes the test worthless.

## A locator

`{window, scope, role, name, match, id, row, column, item, point, nth}`; give at least one of `role`, `name`, `id`.

- `window`: `main`, `popup`, `dialog`, `dialog:<title>`, `window:<title>`; a title ending in `*` matches a prefix.
- `scope`: ordered steps the element sits in: `tab:<title>`, `section:<caption>` (a titled border),
  `id:<name>` (strictly inside the component whose `getName()` is `<name>`). A `tab:` or `section:` step ending
  in `*` matches a title prefix (`tab:New order*` for "New order · Ada Shop Ltd"); this works as a scope step only,
  not as the `name` of the target itself.
- `role`: `window dialog button toggle checkbox radio text password combo list table tree tabs tab menubar menu
  menuitem clickable label section`.
- `name`: component name, own text, caption label, titled border, tooltip or action name. `match:"contains"`
  loosens it. `id`: the component's `getName()`.
- Parts inside the element: `row` (`{"Order":"ORD-1640"}` or `{"index":0}`), `column` (a header; with `row`, that
  cell; alone, it also narrows WHICH table: only one with that header), `item` (list/combo item, or a tree path),
  `point` (`{x,y}` fractions, for custom-painted content).
- `nth`: which match when not unique. It makes the step `fragile`; treat it as a last resort.
- `{"ref":"e12"}` from the last snapshot works for exploring; it is only valid until the next snapshot.

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
harness.

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
  `column` plus `state:["selected"]` is that row's selection; a cell's `state` is its editability.
- `scroll` moves the viewport directly (`to`, `pages`, or a `row` on the target, exactly one); read the result from
  the element's `scroll` block or `expect {"state":["atBottom"]}`. Use it, not `press "Ctrl+End"`, for scrolling.
- On the macOS screen menu bar, `menu` invokes the item directly (recorded `via:"api"`).
- `drag` posts a mouse gesture and never starts a native drag; deliver files or text with `drop`.
- `screenshot` paints the app's own components: open popups, combo dropdowns and tooltips (their own windows) are
  not in the image. Use it for alignment and layout calls only; numbers and booleans are `expect`.
- `hover`, `resizeColumn`, `resizeWindow`, `closeWindow` and `focusFileChooserName` are steps with no tool of
  their own: run them through `do_action` or `batch` (`{"verb":"hover","target":...}`).

## Keep a glossary

When a project is driven more than once, keep its verified targets (screens and their menu paths, table columns,
button ids, dialog titles) in a file in that project, read off the running app, dated. Correct an entry in place
when it turns out wrong; a glossary nobody corrects is worse than none.
