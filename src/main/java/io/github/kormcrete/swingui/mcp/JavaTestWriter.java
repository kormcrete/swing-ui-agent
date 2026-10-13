package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.CloseAction;
import io.github.kormcrete.swingui.flow.ContextMenuAction;
import io.github.kormcrete.swingui.flow.LoginAsAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.verb.AnswerVerb;
import io.github.kormcrete.swingui.flow.verb.CheckVerb;
import io.github.kormcrete.swingui.flow.verb.ChooseFileVerb;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.CloseWindowVerb;
import io.github.kormcrete.swingui.flow.verb.DragVerb;
import io.github.kormcrete.swingui.flow.verb.FocusFileChooserNameVerb;
import io.github.kormcrete.swingui.flow.verb.HoverVerb;
import io.github.kormcrete.swingui.flow.verb.ResizeColumnVerb;
import io.github.kormcrete.swingui.flow.verb.ResizeWindowVerb;
import io.github.kormcrete.swingui.flow.verb.DropVerb;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;
import io.github.kormcrete.swingui.flow.verb.MenuVerb;
import io.github.kormcrete.swingui.flow.verb.PickVerb;
import io.github.kormcrete.swingui.flow.verb.PressVerb;
import io.github.kormcrete.swingui.flow.verb.ScreenshotVerb;
import io.github.kormcrete.swingui.flow.verb.ScrollVerb;
import io.github.kormcrete.swingui.flow.verb.SelectVerb;
import io.github.kormcrete.swingui.flow.verb.SetClipboardVerb;
import io.github.kormcrete.swingui.flow.verb.TypeVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a recorded MCP session (a list of {@link Action}s) into the Java source of a {@code @UiTest}
 * class on the {@code io.github.kormcrete.swingui.junit} API. Emits text only; it does not reference those types.
 *
 * <p>Every problem a step has is collected before anything is thrown, so one {@link ExportRefused}
 * names everything wrong with the recording instead of stopping at the first.
 */
public final class JavaTestWriter {

    private static final String CLASS_NAME_PATTERN = "[A-Z][A-Za-z0-9_]*UiIT";
    private static final String METHOD_NAME_PATTERN = "[a-z][A-Za-z0-9_]*";
    private static final String PACKAGE_NAME_PATTERN = "[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*";

    private JavaTestWriter() {}

    public static String write(List<Action> steps, String packageName, String className, String methodName) {
        return new Writer(steps, packageName, className, methodName).build();
    }

    /** One export: holds the state a single {@link #write} call accumulates. */
    private static final class Writer {

        private final List<Action> steps;
        private final String packageName;
        private final String className;
        private final String methodName;

        private final List<String> problems = new ArrayList<String>();
        private final List<String> statements = new ArrayList<String>();
        /** Whether the last statement checked (true) or acted (false); null before the first. */
        private Boolean checking;

        private String loginAlias;
        private boolean usesDuration;
        private boolean usesRowsRow;
        private boolean usesRowsIndex;
        private boolean usesAssertThat;

        Writer(List<Action> steps, String packageName, String className, String methodName) {
            this.steps = steps;
            this.packageName = packageName;
            this.className = className;
            this.methodName = methodName;
        }

        String build() {
            validateNames();
            if (steps.isEmpty()) {
                problems.add("nothing was recorded");
            } else {
                processSteps();
            }
            if (!problems.isEmpty()) throw new ExportRefused(problems);
            return assemble();
        }

        // ---- names

        private void validateNames() {
            if (className == null) {
                problems.add("class name is missing");
            } else if (!className.matches(CLASS_NAME_PATTERN)) {
                problems.add("class name must match " + CLASS_NAME_PATTERN + " and end in UiIT, was '" + className + "'");
            }
            if (methodName == null) {
                problems.add("method name is missing");
            } else if (!methodName.matches(METHOD_NAME_PATTERN)) {
                problems.add("method name must match " + METHOD_NAME_PATTERN + ", was '" + methodName + "'");
            }
            if (packageName == null) {
                problems.add("package name is missing");
            } else if (!packageName.matches(PACKAGE_NAME_PATTERN)) {
                problems.add("package name must be dotted identifiers, was '" + packageName + "'");
            }
        }

        // ---- steps

        private void processSteps() {
            int start = 0;
            if (steps.get(0) instanceof LoginAsAction) {
                String as = ((LoginAsAction) steps.get(0)).as;
                if (as == null) {
                    problems.add("step 1: loginAs needs an alias");
                } else {
                    loginAlias = as;
                }
                start = 1;
            }
            for (int i = start; i < steps.size(); i++) {
                int stepNum = i + 1;
                Action a = steps.get(i);

                if (a instanceof LoginAsAction) {
                    problems.add("step " + stepNum + ": loginAs is only exportable as the first step");
                    continue;
                }
                // The app steps the Client has a 1:1 form for; any other non-verb step is refused.
                if (a instanceof NavigateAction) {
                    String target = ((NavigateAction) a).target;
                    if (target == null || target.trim().isEmpty()) {
                        problems.add("step " + stepNum + ": navigate needs a target");
                    } else {
                        group(false);
                        statements.add(indent("ui.navigate(" + quote(target) + ");"));
                    }
                    continue;
                }
                if (a instanceof CloseAction) {
                    group(false);
                    statements.add(indent("ui.closeActiveTab();"));
                    continue;
                }
                if (!(a instanceof VerbAction) || (a instanceof ContextMenuAction && ((ContextMenuAction) a).target == null)) {
                    problems.add("step " + stepNum + ": " + a.describe() + " is not a step the Client API has a Java form for");
                    continue;
                }

                VerbAction v = (VerbAction) a;
                if (hasBadLocator(v, stepNum)) continue;

                String stmt = buildStatement(v, stepNum);
                if (stmt == null) {
                    // A problem (e.g. a row with more than two keys) was already recorded; nothing to emit.
                    continue;
                }

                // a screenshot belongs to the group before it: it shows what those steps left on screen
                group(v instanceof ScreenshotVerb ? Boolean.TRUE.equals(checking) : v instanceof ExpectVerb);
                if (v instanceof ScreenshotVerb && ((ScreenshotVerb) v).maxSize != null) {
                    statements.add(indent("// maxSize " + ((ScreenshotVerb) v).maxSize + " dropped"));
                }
                if (Boolean.TRUE.equals(v.fragile)) {
                    statements.add(indent("// FRAGILE: the recorder needed nth to make this target unique"));
                }
                statements.add(indent(stmt));
            }
        }

        /**
         * Actions and the checks after them read as groups: a blank line wherever the body turns from acting to checking
         * or back. {@code checking} is null before the first statement.
         */
        private void group(boolean check) {
            if (checking != null && checking != check) statements.add("");
            checking = check;
        }

        /** True (and a problem recorded per bad locator) when any of this verb's targets uses ref, point or item. */
        private boolean hasBadLocator(VerbAction v, int stepNum) {
            boolean bad = false;
            for (Locator l : v.targets().values()) {
                if (l == null) continue;
                if (l.ref != null || l.point != null || l.item != null) {
                    problems.add("step " + stepNum + ": locator " + l + " uses ref/point/item, which a test cannot express");
                    bad = true;
                }
            }
            return bad;
        }

        private String buildStatement(VerbAction v, int stepNum) {
            if (v instanceof ClickVerb) return buildClick((ClickVerb) v, stepNum);
            if (v instanceof TypeVerb) return buildType((TypeVerb) v, stepNum);
            if (v instanceof PressVerb) return buildPress((PressVerb) v, stepNum);
            if (v instanceof CheckVerb) return buildCheck((CheckVerb) v, stepNum);
            if (v instanceof SelectVerb) return buildSelect((SelectVerb) v, stepNum);
            if (v instanceof PickVerb) return buildPick((PickVerb) v);
            if (v instanceof MenuVerb) return buildMenu((MenuVerb) v);
            if (v instanceof AnswerVerb) return buildAnswer((AnswerVerb) v);
            if (v instanceof ChooseFileVerb) return buildChooseFile((ChooseFileVerb) v);
            if (v instanceof DragVerb) return buildDrag((DragVerb) v, stepNum);
            if (v instanceof DropVerb) return buildDrop((DropVerb) v, stepNum);
            if (v instanceof ScrollVerb) return buildScroll((ScrollVerb) v, stepNum);
            if (v instanceof SetClipboardVerb) return clientRoot(v.timeout) + ".setClipboard(" + quote(((SetClipboardVerb) v).text) + ");";
            if (v instanceof ScreenshotVerb) return buildScreenshot((ScreenshotVerb) v, stepNum);
            if (v instanceof ContextMenuAction) return buildContextMenu((ContextMenuAction) v, stepNum);
            if (v instanceof ExpectVerb) return buildExpect((ExpectVerb) v, stepNum);
            if (v instanceof HoverVerb) {
                HoverVerb h = (HoverVerb) v;
                String chain = actionChain(h.target, h.timeout, stepNum);
                return chain == null ? null : chain + (Boolean.FALSE.equals(h.inside) ? ".unhover();" : ".hover();");
            }
            if (v instanceof ResizeColumnVerb) {
                ResizeColumnVerb r = (ResizeColumnVerb) v;
                String chain = actionChain(r.target, r.timeout, stepNum);
                return chain == null ? null : chain + ".resizeColumn(" + r.delta + ");";
            }
            if (v instanceof ResizeWindowVerb) {
                ResizeWindowVerb r = (ResizeWindowVerb) v;
                return windowChain(r.window, r.timeout) + ".resizeTo(" + r.width + ", " + r.height + ");";
            }
            if (v instanceof CloseWindowVerb) {
                return windowChain(((CloseWindowVerb) v).window, v.timeout) + ".close();";
            }
            if (v instanceof FocusFileChooserNameVerb) return clientRoot(v.timeout) + ".focusFileChooserName();";
            // Every VerbAction subtype is one of the above; a new one needs a table row here too.
            problems.add("step " + stepNum + ": " + v.describe() + " is a verb this writer has no Java form for");
            return null;
        }

        // ---- per-verb statements

        private String buildClick(ClickVerb v, int stepNum) {
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            boolean leftOrNull = v.button == null || "left".equals(v.button);
            boolean countDefault = v.count == null || v.count == 1;
            boolean noMods = v.modifiers == null || v.modifiers.isEmpty();
            if (leftOrNull && countDefault && noMods) return chain + ".click();";
            if (leftOrNull && noMods && v.count != null && v.count == 2) return chain + ".doubleClick();";

            StringBuilder args = new StringBuilder();
            args.append(quote(v.button != null ? v.button : "left"));
            args.append(", ").append(v.count != null ? v.count : 0);
            if (v.modifiers != null) {
                for (String m : v.modifiers) args.append(", ").append(quote(m));
            }
            return chain + ".click(" + args + ");";
        }

        private String buildType(TypeVerb v, int stepNum) {
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            if (Boolean.TRUE.equals(v.replace) && Boolean.TRUE.equals(v.commit)) {
                return chain + ".fill(" + quote(v.text) + ");";
            }
            if (v.replace == null && v.commit == null) {
                return chain + ".type(" + quote(v.text) + ");";
            }
            return chain + ".type(" + quote(v.text) + ", " + boolLiteral(v.replace) + ", " + boolLiteral(v.commit) + ");";
        }

        private String buildPress(PressVerb v, int stepNum) {
            if (v.target == null) {
                return clientRoot(v.timeout) + ".press(" + quote(v.keys) + ");";
            }
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            return chain + ".press(" + quote(v.keys) + ");";
        }

        private String buildCheck(CheckVerb v, int stepNum) {
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            boolean on = v.checked == null || v.checked;
            return chain + (on ? ".check();" : ".uncheck();");
        }

        private String buildSelect(SelectVerb v, int stepNum) {
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            if (v.item != null) return chain + ".select(" + quote(v.item) + ");";
            return chain + ".select(" + joinQuoted(v.items) + ");";
        }

        private String buildPick(PickVerb v) {
            return clientRoot(v.timeout) + ".pick(" + joinQuoted(v.path) + ");";
        }

        private String buildMenu(MenuVerb v) {
            return clientRoot(v.timeout) + ".menu(" + joinQuoted(v.path) + ");";
        }

        private String buildAnswer(AnswerVerb v) {
            return clientRoot(v.timeout) + ".answer(" + quote(v.button) + ");";
        }

        private String buildChooseFile(ChooseFileVerb v) {
            String args = quote(v.path);
            if (v.approve != null) args += ", " + (v.approve ? "true" : "false");
            return clientRoot(v.timeout) + ".chooseFile(" + args + ");";
        }

        private String buildDrag(DragVerb v, int stepNum) {
            String fromChain = actionChain(v.from, v.timeout, stepNum);
            if (fromChain == null) return null;
            String toChain = chain(v.to, stepNum);
            if (toChain == null) return null;
            return fromChain + ".dragTo(" + toChain + ");";
        }

        private String buildDrop(DropVerb v, int stepNum) {
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            if (v.files != null) return chain + ".drop(" + joinQuoted(v.files) + ");";
            return chain + ".dropText(" + quote(v.text) + ");";
        }

        /** Exactly one of to/pages/row: a row scroll is the target's row chain plus {@code scrollIntoView()}. */
        private String buildScroll(ScrollVerb v, int stepNum) {
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            if (v.to != null) return chain + ".scrollTo(" + quote(v.to) + ");";
            if (v.pages != null) return chain + ".scrollBy(" + v.pages + ");";
            return chain + ".scrollIntoView();";
        }

        private String buildScreenshot(ScreenshotVerb v, int stepNum) {
            if (v.window == null && v.target == null) {
                return clientRoot(v.timeout) + ".saveScreenshot(" + quote(v.path) + scaleArg(v.scale) + ");";
            }
            if (v.target != null) {
                String chain = actionChain(v.target, v.timeout, stepNum);
                if (chain == null) return null;
                return chain + ".saveScreenshot(" + quote(v.path) + scaleArg(v.scale) + ");";
            }
            StringBuilder sb = new StringBuilder("ui.inWindow(").append(quote(v.window)).append(")");
            if (v.timeout != null) {
                usesDuration = true;
                sb.append(".timeout(Duration.ofMillis(").append(v.timeout).append("L))");
            }
            sb.append(".saveScreenshot(").append(quote(v.path)).append(scaleArg(v.scale)).append(");");
            return sb.toString();
        }

        /** ", 2" for a scaled screenshot (", 1.5" when not whole), "" without a scale: unscaled output is unchanged. */
        private String scaleArg(Double scale) {
            if (scale == null) return "";
            return scale == Math.rint(scale) ? ", " + scale.longValue() : ", " + scale;
        }

        private String buildContextMenu(ContextMenuAction v, int stepNum) {
            String chain = actionChain(v.target, v.timeout, stepNum);
            if (chain == null) return null;
            return chain + ".rightClick(" + joinQuoted(v.path) + ");";
        }

        private String buildExpect(ExpectVerb v, int stepNum) {
            usesAssertThat = true;
            String chain = chain(v.target, stepNum);
            if (chain == null) return null;
            StringBuilder sb = new StringBuilder("assertThat(").append(chain).append(")");
            if (v.timeout != null) {
                usesDuration = true;
                sb.append(".within(Duration.ofMillis(").append(v.timeout).append("L))");
            }
            if (v.state != null) {
                sb.append(".").append(stateMethod(v.state));
            } else if (v.text != null) {
                sb.append(".hasText(").append(quote(v.text)).append(")");
            } else if (v.value != null) {
                sb.append(".hasValue(").append(quote(v.value)).append(")");
            } else if (v.tooltip != null) {
                sb.append(".hasTooltip(").append(quote(v.tooltip)).append(")");
            } else if (v.rows != null) {
                if ("eq".equals(v.rows.op)) {
                    sb.append(".hasRowCount(").append(v.rows.value).append(")");
                } else {
                    sb.append(".hasRowCount(").append(quote(v.rows.op)).append(", ").append(v.rows.value).append(")");
                }
            } else if (v.cell != null) {
                String rowExpr = rowValueExpr(v.cell.row, stepNum);
                if (rowExpr == null) return null;
                sb.append(".hasCell(").append(rowExpr).append(", ").append(quote(v.cell.column))
                        .append(", ").append(quote(v.cell.expected)).append(")");
            } else if (v.selected != null) {
                sb.append(".hasSelectedRows(").append(joinInts(v.selected)).append(")");
            } else {
                problems.add("step " + stepNum + ": " + v.describe() + " has nothing to check");
                return null;
            }
            sb.append(";");
            return sb.toString();
        }

        private static String stateMethod(List<String> state) {
            if (state.size() == 1) {
                String s = state.get(0);
                if ("disabled".equals(s)) return "isDisabled()";
                if ("!disabled".equals(s)) return "isEnabled()";
                if ("checked".equals(s)) return "isChecked()";
                if ("!checked".equals(s)) return "isNotChecked()";
                if ("selected".equals(s)) return "isSelected()";
                if ("!selected".equals(s)) return "isNotSelected()";
                if ("!hidden".equals(s)) return "isVisible()";
                if ("hidden".equals(s)) return "isHidden()";
                if ("editable".equals(s)) return "isEditable()";
                if ("readonly".equals(s)) return "isReadOnly()";
                return "hasStates(" + quote(s) + ")";
            }
            return "hasStates(" + joinQuoted(state) + ")";
        }

        // ---- locator chains

        /** A Target chain with a trailing {@code .timeout(...)} when the step recorded one. */
        private String actionChain(Locator l, Long timeout, int stepNum) {
            String c = chain(l, stepNum);
            if (c == null) return null;
            if (timeout != null) {
                usesDuration = true;
                c = c + ".timeout(Duration.ofMillis(" + timeout + "L))";
            }
            return c;
        }

        /** The client root for a target-less verb: plain {@code ui}, or {@code ui.timeout(...)}. */
        private String clientRoot(Long timeout) {
            if (timeout == null) return "ui";
            usesDuration = true;
            return "ui.timeout(Duration.ofMillis(" + timeout + "L))";
        }

        /** A window root as the Client API names it: ui.main(), ui.dialog("T"), ui.window("T"), ui.inWindow("token"). */
        private String windowChain(String window, Long timeout) {
            String root;
            if (window == null || "main".equals(window)) root = "ui.main()";
            else if ("dialog".equals(window)) root = "ui.dialog()";
            else if ("popup".equals(window)) root = "ui.popup()";
            else if (window.startsWith("dialog:")) root = "ui.dialog(" + quote(window.substring("dialog:".length())) + ")";
            else if (window.startsWith("window:")) root = "ui.window(" + quote(window.substring("window:".length())) + ")";
            else root = "ui.inWindow(" + quote(window) + ")";
            if (timeout == null) return root;
            usesDuration = true;
            return root + ".timeout(Duration.ofMillis(" + timeout + "L))";
        }

        /** {@code null} when the locator's row has more than two keys - the problem is already recorded. */
        private String chain(Locator l, int stepNum) {
            StringBuilder sb = new StringBuilder("ui");
            appendWindow(sb, l.window);
            if (l.scope != null) {
                for (String step : l.scope) appendScopeStep(sb, step);
            }
            appendLeaf(sb, l);
            if (l.row != null) {
                String rowChain = rowChain(l.row, stepNum);
                if (rowChain == null) return null;
                sb.append(rowChain);
            }
            if (l.column != null) sb.append(".cell(").append(quote(l.column)).append(")");
            if (l.nth != null) sb.append(".nth(").append(l.nth).append(")");
            if (l.containsMatch()) sb.append(".contains()");
            return sb.toString();
        }

        private static void appendWindow(StringBuilder sb, String window) {
            if (window == null) return;
            if ("main".equals(window)) { sb.append(".main()"); return; }
            if ("popup".equals(window)) { sb.append(".popup()"); return; }
            if ("dialog".equals(window)) { sb.append(".dialog()"); return; }
            if (window.startsWith("dialog:")) { sb.append(".dialog(").append(quote(window.substring(7))).append(")"); return; }
            if (window.startsWith("window:")) { sb.append(".window(").append(quote(window.substring(7))).append(")"); return; }
            throw new IllegalStateException("window must be main, popup, dialog, dialog:<title> or window:<title>, was '" + window + "'");
        }

        private static void appendScopeStep(StringBuilder sb, String step) {
            if (step.startsWith("tab:")) { sb.append(".tab(").append(quote(step.substring(4))).append(")"); return; }
            if (step.startsWith("section:")) { sb.append(".section(").append(quote(step.substring(8))).append(")"); return; }
            // A bare byId(x) chained into is Target's id scope, so this reads back as the same step.
            if (step.startsWith("id:")) { sb.append(".byId(").append(quote(step.substring(3))).append(")"); return; }
            throw new IllegalStateException("scope step must be tab:<title>, section:<caption> or id:<name>, was '" + step + "'");
        }

        /** {@code id != null} wins (a role alongside an id is dropped - the id is unique). */
        private static void appendLeaf(StringBuilder sb, Locator l) {
            if (l.id != null) {
                sb.append(".byId(").append(quote(l.id)).append(")");
                return;
            }
            if (l.role != null) {
                appendRoleLeaf(sb, l.role, l.name);
                return;
            }
            if (l.name != null) {
                sb.append(".role(null, ").append(quote(l.name)).append(")");
            }
        }

        /**
         * The recorder's own fragile fallback (driver/model/Recorder.java) can produce a role with no
         * name at all, e.g. {@code {role:"text", nth:3}} - so every leaf method below takes a literal
         * {@code null} rather than {@link #quote}, which NPEs on it. Each of these Target methods has
         * exactly one single-String overload, so a bare {@code null} argument is never ambiguous.
         */
        private static void appendRoleLeaf(StringBuilder sb, String role, String name) {
            if ("button".equals(role)) { sb.append(".button(").append(literal(name)).append(")"); return; }
            if ("text".equals(role)) { sb.append(".field(").append(literal(name)).append(")"); return; }
            if ("password".equals(role)) { sb.append(".password(").append(literal(name)).append(")"); return; }
            if ("combo".equals(role)) { sb.append(".combo(").append(literal(name)).append(")"); return; }
            if ("checkbox".equals(role)) { sb.append(".checkbox(").append(literal(name)).append(")"); return; }
            if ("radio".equals(role)) { sb.append(".radio(").append(literal(name)).append(")"); return; }
            if ("toggle".equals(role)) { sb.append(".toggle(").append(literal(name)).append(")"); return; }
            if ("list".equals(role)) { sb.append(".list(").append(literal(name)).append(")"); return; }
            if ("tree".equals(role)) { sb.append(".tree(").append(literal(name)).append(")"); return; }
            if ("table".equals(role)) {
                if (name == null) sb.append(".table()"); else sb.append(".table(").append(quote(name)).append(")");
                return;
            }
            if ("tab".equals(role)) { sb.append(".tab(").append(literal(name)).append(")"); return; }
            if ("section".equals(role)) { sb.append(".section(").append(literal(name)).append(")"); return; }
            if ("label".equals(role)) { sb.append(".label(").append(literal(name)).append(")"); return; }
            if ("clickable".equals(role)) { sb.append(".clickable(").append(literal(name)).append(")"); return; }
            if ("menuitem".equals(role)) { sb.append(".menuitem(").append(literal(name)).append(")"); return; }
            sb.append(".role(").append(quote(role)).append(", ").append(literal(name)).append(")");
        }

        // ---- rows

        /** The chain suffix for a locator's row: {@code .rowAt(n)}, {@code .row("c", v)} or {@code .row(row(...))}. */
        private String rowChain(Map<String, Object> row, int stepNum) {
            if (isIndexRow(row)) return ".rowAt(" + intLiteral(row) + ")";
            if (row.size() == 1) {
                Map.Entry<String, Object> e = row.entrySet().iterator().next();
                return ".row(" + quote(e.getKey()) + ", " + valueLiteral(e.getValue()) + ")";
            }
            if (row.size() == 2) {
                usesRowsRow = true;
                return ".row(row(" + rowArgs(row) + "))";
            }
            problems.add("step " + stepNum + ": row with more than two keys");
            return null;
        }

        /** The value expression for a row used as an argument (e.g. {@code hasCell}'s row), not a chain call. */
        private String rowValueExpr(Map<String, Object> row, int stepNum) {
            if (isIndexRow(row)) {
                usesRowsIndex = true;
                return "index(" + intLiteral(row) + ")";
            }
            if (row.size() == 1 || row.size() == 2) {
                usesRowsRow = true;
                return "row(" + rowArgs(row) + ")";
            }
            problems.add("step " + stepNum + ": row with more than two keys");
            return null;
        }

        private static boolean isIndexRow(Map<String, Object> row) {
            return row.size() == 1 && row.get("index") instanceof Number;
        }

        private static String intLiteral(Map<String, Object> row) {
            return String.valueOf(((Number) row.get("index")).intValue());
        }

        private static String rowArgs(Map<String, Object> row) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(quote(e.getKey())).append(", ").append(valueLiteral(e.getValue()));
                first = false;
            }
            return sb.toString();
        }

        private static String valueLiteral(Object v) {
            if (v instanceof Number) return v.toString();
            return quote(String.valueOf(v));
        }

        // ---- assembly

        private String assemble() {
            StringBuilder sb = new StringBuilder();
            sb.append("package ").append(packageName).append(";\n\n");

            sb.append("import io.github.kormcrete.swingui.junit.Client;\n");
            if (loginAlias != null) sb.append("import io.github.kormcrete.swingui.junit.LoginAs;\n");
            sb.append("import io.github.kormcrete.swingui.junit.UiTest;\n");
            sb.append("import org.junit.jupiter.api.Test;\n");

            if (usesDuration) {
                sb.append("\nimport java.time.Duration;\n");
            }

            List<String> staticImports = new ArrayList<String>();
            if (usesRowsIndex) staticImports.add("import static io.github.kormcrete.swingui.junit.Rows.index;");
            if (usesRowsRow) staticImports.add("import static io.github.kormcrete.swingui.junit.Rows.row;");
            if (usesAssertThat) staticImports.add("import static io.github.kormcrete.swingui.junit.UiAssertions.assertThat;");
            if (!staticImports.isEmpty()) {
                sb.append("\n");
                for (String s : staticImports) sb.append(s).append("\n");
            }

            sb.append("\n");
            sb.append("/** Recorded through swing-ui-agent. Replace literals the test creates with ui.runId(). */\n");
            sb.append("@UiTest\n");
            sb.append("class ").append(className).append(" {\n\n");
            sb.append("    @Test\n");
            if (loginAlias != null) {
                sb.append("    @LoginAs(").append(quote(loginAlias)).append(")\n");
            }
            sb.append("    void ").append(methodName).append("(Client ui) {\n");
            for (String stmt : statements) {
                sb.append(stmt).append("\n");
            }
            sb.append("    }\n");
            sb.append("}\n");
            return sb.toString();
        }

        private static String indent(String s) {
            return "        " + s;
        }
    }

    // ---- literals

    private static String boolLiteral(Boolean b) {
        return b == null ? "null" : (b ? "Boolean.TRUE" : "Boolean.FALSE");
    }

    private static String joinQuoted(List<String> items) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : items) {
            if (!first) sb.append(", ");
            sb.append(quote(s));
            first = false;
        }
        return sb.toString();
    }

    private static String joinInts(List<Integer> ints) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer i : ints) {
            if (!first) sb.append(", ");
            sb.append(i);
            first = false;
        }
        return sb.toString();
    }

    /** {@code s} as a Java argument: the literal word {@code null} for a null name, else a quoted string. */
    private static String literal(String s) {
        return s == null ? "null" : quote(s);
    }

    /** A Java string literal, backslash/quote/newline/CR/tab escaped; non-ASCII kept as-is (source is UTF-8). */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
