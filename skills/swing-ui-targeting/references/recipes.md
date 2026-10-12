# Recipes: job, live step, exported Java

Each live step is a tool call (`$UI <tool> '<json>'`) or a `batch` step (`{"verb":"<tool>", ...}`). The Java column is
the form `export_test` writes (the recorder may add a window or scope to the chain). `ui` is the test's `Client`;
`row(...)` is `Rows.row`, `assertThat` is `UiAssertions.assertThat`.

| Job | Live step | Java |
|---|---|---|
| open a screen by menu | `menu {"path":["Orders","Open orders"]}` | `ui.menu("Orders", "Open orders");` |
| open a screen by shortcut | `press {"keys":"Mod+O"}` | `ui.press("Mod+O");` |
| open a screen (plugin) | `do_action {"action":{"action":"navigate","target":"orders"}}` | `ui.navigate("orders");` |
| open a row by values | `click {"target":{"role":"table","name":"Orders","row":{"Order":"ORD-1640"}},"count":2}` | `ui.table("Orders").row("Order", "ORD-1640").doubleClick();` |
| open the first row | `click {"target":{"role":"table","row":{"index":0}},"count":2}` | `ui.table().rowAt(0).doubleClick();` |
| add a row to the selection | `click {"target":{...,"row":{"index":2}},"modifiers":["Mod"]}` | `.rowAt(2).click("left", 0, "Mod");` |
| select a tab | `click {"target":{"role":"tab","name":"Line items"}}` | `ui.tab("Line items").click();` |
| a table on that tab | `{"scope":["tab:Line items"],"role":"table"}` | `ui.tab("Line items").table()` |
| fill a field | `type {"target":{"role":"text","name":"Customer"},"text":"Acme","replace":true,"commit":true}` | `ui.field("Customer").fill("Acme");` |
| edit a cell | `type {"target":{"role":"table","row":{"index":0},"column":"Quantity"},"text":"3","commit":true}` | `.rowAt(0).cell("Quantity").type("3", null, true);` |
| pick a combo value | `select {"target":{"role":"combo","name":"Status"},"item":"Open"}` | `ui.combo("Status").select("Open");` |
| tick a check box | `check {"target":{"role":"checkbox","name":"Express"},"checked":true}` | `ui.checkbox("Express").check();` |
| a button | `click {"target":{"role":"button","name":"Save"}}` | `ui.button("Save").click();` |
| a dropdown button's item | `click` the button, then `pick {"path":["Export","PDF"]}` | `ui.pick("Export", "PDF");` |
| a context menu | `context_menu {"target":{"role":"table","row":{"index":0}},"path":["Duplicate"]}` | `.rowAt(0).rightClick("Duplicate");` |
| answer a dialog | `answer {"button":"Yes"}` (`"escape"` dismisses) | `ui.answer("Yes");` |
| choose a file | `choose_file {"path":"/tmp/orders.csv"}` | `ui.chooseFile("/tmp/orders.csv");` |
| paste a block | `set_clipboard {"text":"a\tb\n1\t2"}`, click the anchor cell, `press {"keys":"Mod+V"}` | `ui.setClipboard(...); ...; ui.press("Mod+V");` |
| scroll a table | `scroll {"target":{"role":"table","name":"Orders"},"to":"bottom"}` | `ui.table("Orders").scrollTo("bottom");` |
| check a field | `expect {"target":{"role":"text","name":"Customer"},"value":"Acme"}` | `assertThat(ui.field("Customer")).hasValue("Acme");` |
| check a cell | `expect {"target":{"role":"table","name":"Orders"},"cell":{"row":{"Order":"ORD-1640"},"column":"Status","expected":"Open"}}` | `assertThat(ui.table("Orders")).hasCell(row("Order", "ORD-1640"), "Status", "Open");` |
| check a row count | `expect {"target":{"role":"table","name":"Orders"},"rows":{"op":"gte","value":1}}` | `assertThat(ui.table("Orders")).hasRowCount("gte", 1);` |
| check enabled | `expect {"target":{"role":"button","name":"Save"},"state":["!disabled"]}` | `assertThat(ui.button("Save")).isEnabled();` |
| check the selection | `expect {"target":{"role":"table","name":"Orders"},"selected":[0]}` | `assertThat(ui.table("Orders")).hasSelectedRows(0);` |
| close the active tab (plugin) | `do_action {"action":{"action":"close"}}` | `ui.closeActiveTab();` |

Read-only, never recorded: `snapshot`, `read`, `screenshot`, `describe_ui`, `read_clipboard`, `read_file_chooser`.
In Java the same reads are `Target.value()`, `.rows()`, `.columns()`, `.rowCount()`, `.states()`, `ui.clipboardText()`,
`ui.fileChooser()`, `ui.describeModal()`.

Not exportable (the step runs, the result says `notRecorded`): a target with `item` or `point`, a `row` with more than
two columns, `loginAs` after the first step. A leading `loginAs` exports as `@LoginAs("<alias>")` (plugin).
