# Recipes: job, live step, exported Java

Each live step is a tool call (`$UI <tool> '<json>'`) or a `batch` step (`{"verb":"<tool>", ...}`). The Java column is
the form `export_test` writes (the recorder may add a window or scope to the chain). `ui` is the test's `Client`;
`row(...)` is `Rows.row`, `assertThat` is `UiAssertions.assertThat`.

| Job | Live step | Java |
|---|---|---|
| open a screen by menu | `menu {"path":["File","New order"]}` | `ui.menu("File", "New order");` |
| open a screen by shortcut | `press {"keys":"Mod+N"}` | `ui.press("Mod+N");` |
| open a screen (plugin) | `do_action {"action":{"action":"navigate","target":"orders"}}` | `ui.navigate("orders");` |
| open a row by values | `click {"target":{"scope":["tab:Orders"],"role":"table","row":{"Order":"ORD-1640"}},"count":2}` | `ui.tab("Orders").table().row("Order", "ORD-1640").doubleClick();` |
| open the first row | `click {"target":{"role":"table","row":{"index":0}},"count":2}` | `ui.table().rowAt(0).doubleClick();` |
| add a row to the selection | `click {"target":{...,"row":{"index":2}},"modifiers":["Mod"]}` | `.rowAt(2).click("left", 0, "Mod");` |
| select a tab | `click {"target":{"role":"tab","name":"Items"}}` | `ui.tab("Items").click();` |
| a table on that tab | `{"scope":["tab:Items"],"role":"table"}` | `ui.tab("Items").table()` |
| fill a field | `type {"target":{"role":"text","name":"PO number"},"text":"PO-7781","replace":true,"commit":true}` | `ui.field("PO number").fill("PO-7781");` |
| edit a cell | `type {"target":{"role":"table","row":{"Product":"Gel pen, black"},"column":"Qty"},"text":"10","commit":true}` | `.row("Product", "Gel pen, black").cell("Qty").type("10", null, true);` |
| pick a combo value | `select {"target":{"role":"combo","name":"Status"},"item":"Placed"}` | `ui.combo("Status").select("Placed");` |
| choose a radio button | `click {"target":{"window":"dialog","role":"radio","name":"Express · next business day"}}` | `ui.dialog().radio("Express · next business day").click();` |
| tick a check box | `check {"target":{"role":"checkbox","name":"<label>"},"checked":true}` | `ui.checkbox("<label>").check();` |
| a button | `click {"target":{"role":"button","name":"Add to cart"}}` | `ui.button("Add to cart").click();` |
| a dropdown button's item | `click` the button, then `pick {"path":["Ada Shop Ltd"]}` | `ui.pick("Ada Shop Ltd");` |
| a context menu | `context_menu {"target":{"role":"table","row":{"Order":"ORD-1636"}},"path":["Copy order number"]}` | `.row("Order", "ORD-1636").rightClick("Copy order number");` |
| answer a dialog | `answer {"button":"Place order"}` (`"escape"` dismisses) | `ui.answer("Place order");` |
| choose a file | `choose_file {"path":"/tmp/ORD-1640.txt"}` | `ui.chooseFile("/tmp/ORD-1640.txt");` |
| paste a block | `set_clipboard {"text":"a\tb\n1\t2"}`, click the anchor cell, `press {"keys":"Mod+V"}` | `ui.setClipboard(...); ...; ui.press("Mod+V");` |
| scroll a table | `scroll {"target":{"scope":["tab:Orders"],"role":"table"},"to":"bottom"}` | `ui.tab("Orders").table().scrollTo("bottom");` |
| check a field | `expect {"target":{"window":"dialog","role":"text","name":"Postal code"},"value":"10119"}` | `assertThat(ui.dialog().field("Postal code")).hasValue("10119");` |
| check a cell | `expect {"target":{"scope":["tab:Orders"],"role":"table"},"cell":{"row":{"Order":"ORD-1639"},"column":"Status","expected":"Placed"}}` | `assertThat(ui.tab("Orders").table()).hasCell(row("Order", "ORD-1639"), "Status", "Placed");` |
| check a row count | `expect {"target":{"scope":["tab:Orders"],"role":"table"},"rows":{"op":"gte","value":1}}` | `assertThat(ui.tab("Orders").table()).hasRowCount("gte", 1);` |
| check a value next to a caption | `expect {"target":{"role":"label","name":"Total"},"text":"€31.32"}` | `assertThat(ui.label("Total")).hasText("€31.32");` |
| check enabled | `expect {"target":{"role":"button","name":"Checkout…"},"state":["!disabled"]}` | `assertThat(ui.button("Checkout…")).isEnabled();` |
| check the selection | `expect {"target":{"scope":["tab:Orders"],"role":"table"},"selected":[0]}` | `assertThat(ui.tab("Orders").table()).hasSelectedRows(0);` |
| close the active tab (plugin) | `do_action {"action":{"action":"close"}}` | `ui.closeActiveTab();` |

Read-only, never recorded: `snapshot`, `read`, `screenshot`, `describe_ui`, `read_clipboard`, `read_file_chooser`.
In Java the same reads are `Target.value()`, `.rows()`, `.columns()`, `.rowCount()`, `.states()`, `ui.clipboardText()`,
`ui.fileChooser()`, `ui.describeModal()`.

Not exportable (the step runs, the result says `notRecorded`): a target with `item` or `point`, a `row` with more than
two columns, `loginAs` after the first step. A leading `loginAs` exports as `@LoginAs("<alias>")` (plugin).
