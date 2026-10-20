package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.CloseAction;
import io.github.kormcrete.swingui.flow.DoAction;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowGroup;
import io.github.kormcrete.swingui.flow.ContextMenuAction;
import io.github.kormcrete.swingui.flow.LoginAsAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.StepNumbers;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a recorded MCP session (a list of {@link Action}s) into the Java source of a {@code @UiTest}
 * class on the {@code io.github.kormcrete.swingui.junit} API. Emits text only; it does not reference those types.
 *
 * <p>A {@code do} step, a plugin's domain step, is written as its {@link DoForms} says: the typed call the step names
 * ({@code OrderDeskSteps.addToCart(ui, "Gel pen, black");}, its class imported), else the steps it makes, under its
 * sentence as a comment; a step with neither is refused.
 *
 * <p>Every problem a step has is collected before anything is thrown, so one {@link ExportRefused}
 * names everything wrong with the recording instead of stopping at the first.
 */
public final class JavaTestWriter {

    private static final String CLASS_NAME_PATTERN = "[A-Z][A-Za-z0-9_]*UiIT";
    private static final String METHOD_NAME_PATTERN = "[a-z][A-Za-z0-9_]*";
    private static final String PACKAGE_NAME_PATTERN = "[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*";

    private static final String FRAGILE = "FRAGILE: the recorder needed nth to make this target unique";
    private static final String FRAGILE_POINT = "FRAGILE: clicked at a point: breaks if the layout moves";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]\\w*)\\}");
    /** Simple names the class already uses: a do step's class of the same name is written by its full name. */
    private static final Set<String> TAKEN = new HashSet<String>(Arrays.asList("Client", "LoginAs", "UiTest", "Test",
            "Duration", "Boolean", "String", "Integer", "Object", "System", "Math"));

    private JavaTestWriter() {}

    /** How {@link #write} writes a {@code do} step: through the vocabulary of the plugin that offers it. */
    public interface DoForms {
        /** No vocabulary here: every do step is refused. */
        DoForms NONE = step -> DoForm.refused(step.describe() + " has no Java form here: a do step is written through "
                + "the plugin that offers it, and none is configured");

        /** {@code step}'s Java form; never null. */
        DoForm of(DoAction step);

        /**
         * True for a recording's forms ({@link DoStepForms#keeping}): a do step the writer cannot write - its expansion
         * holds a step with no Java form, its call names no value - is left out like a {@link DoForm#withoutJava} one,
         * with no problem. False: it is a problem, as export_test must say.
         */
        default boolean keeps() {
            return false;
        }
    }

    /**
     * A do step's Java form: a typed call, the steps it makes, or why it has neither: refused (a problem), or, for a
     * recording that may hold such a step, left out ({@link #withoutJava}: no statement, no problem).
     */
    public static final class DoForm {
        final String sentence;
        final String javaClass;
        final String call;
        final Map<String, String> values;
        final Set<String> numbers;
        final List<Action> steps;
        final String refused;
        final boolean leftOut;

        private DoForm(String sentence, String javaClass, String call, Map<String, String> values, Set<String> numbers,
                       List<Action> steps, String refused, boolean leftOut) {
            this.sentence = sentence;
            this.javaClass = javaClass;
            this.call = call;
            this.values = values;
            this.numbers = numbers;
            this.steps = steps;
            this.refused = refused;
            this.leftOut = leftOut;
        }

        /**
         * The static method call {@code call} of {@code javaClass}, e.g. {@code addQtyToCart(ui, {product}, {qty})}: each
         * {@code {name}} becomes the value as a Java literal, a number for a name in {@code numberParams}.
         */
        public static DoForm call(String sentence, String javaClass, String call, Map<String, String> values,
                                  Set<String> numberParams) {
            return new DoForm(sentence, javaClass, call, new LinkedHashMap<String, String>(values),
                    new HashSet<String>(numberParams), null, null, false);
        }

        /** The steps the domain step makes, written under its sentence. */
        public static DoForm steps(String sentence, List<Action> steps) {
            return new DoForm(sentence, null, null, null, null, Collections.unmodifiableList(new ArrayList<Action>(steps)), null,
                    false);
        }

        /** No Java form: {@code why} is the whole problem after "step 3: ", e.g. "Log in as the clerk (...) has no Java form: ...". */
        public static DoForm refused(String why) {
            return new DoForm(null, null, null, null, null, null, why, false);
        }

        /**
         * A step with no Java form ({@code why}, as {@link #refused} gives it) that a recording keeps: the writer leaves it
         * out, with no problem and no {@link #lines} entry (null), so the rest of the recording is still checked.
         */
        public static DoForm withoutJava(String why) {
            return new DoForm(null, null, null, null, null, null, why, true);
        }

        /** Why the step has no Java form; null when it has one. */
        public String why() {
            return refused;
        }
    }

    public static String write(List<Action> steps, String packageName, String className, String methodName) {
        return write(plain(steps), packageName, className, methodName, DoForms.NONE);
    }

    /** {@link #write(List, String, String, String)} with {@code do} steps written as {@code forms} says. */
    public static String write(List<Action> steps, String packageName, String className, String methodName, DoForms forms) {
        return write(plain(steps), packageName, className, methodName, forms);
    }

    /** The class for a recorded test: a step (its action and its checks) is one block; sentences and groups become comments. */
    public static String write(Flow flow, String packageName, String className, String methodName) {
        return write(flow, packageName, className, methodName, DoForms.NONE);
    }

    /** {@link #write(Flow, String, String, String)} with {@code do} steps written as {@code forms} says. */
    public static String write(Flow flow, String packageName, String className, String methodName, DoForms forms) {
        return new Writer(flow, packageName, className, methodName, forms).build();
    }

    private static Flow plain(List<Action> steps) {
        Flow f = new Flow();
        f.steps = steps;
        return f;
    }

    /**
     * One Java entry per step, without indent: the statement the step becomes in {@link #write}'s class body (a
     * leading {@code loginAs} is the {@code @LoginAs} annotation; a fragile step carries the writer's comment after
     * its statement). Refuses exactly what {@code write} refuses.
     */
    public static List<String> lines(List<Action> steps) {
        return lines(plain(steps));
    }

    /** {@link #lines(List)} of a recorded test: still one entry per record (action or check), without comments. */
    public static List<String> lines(Flow flow) {
        return lines(flow, DoForms.NONE);
    }

    /** {@link #lines(Flow)} with {@code do} steps as {@code forms} says; an expanded one's statements are one entry, one per line. */
    public static List<String> lines(Flow flow, DoForms forms) {
        Writer w = new Writer(flow, "lines", "LinesUiIT", "lines", forms);
        w.build();
        return new ArrayList<String>(w.stepLines);
    }

    /** One export: holds the state a single {@link #write} call accumulates. */
    private static final class Writer {

        private final List<Action> steps;
        private final String packageName;
        private final String className;
        private final String methodName;

        private final List<String> problems = new ArrayList<String>();
        private final List<String> statements = new ArrayList<String>();
        /** The Java entry of each step, by step index, as {@link #lines} reports it. */
        private final List<String> stepLines = new ArrayList<String>();
        /** Whether the last statement checked (true) or acted (false); null before the first. */
        private Boolean checking;

        private String loginAlias;
        private boolean usesDuration;
        private boolean usesRowsRow;
        private boolean usesRowsIndex;
        private boolean usesAssertThat;

        private final List<FlowGroup> groups;
        private final DoForms doForms;
        /** The classes of do steps' typed calls, by the simple name the calls use; imported. */
        private final Map<String, String> doClasses = new LinkedHashMap<String, String>();

        Writer(Flow flow, String packageName, String className, String methodName, DoForms doForms) {
            this.steps = flow.steps;
            this.groups = flow.groups == null ? new ArrayList<FlowGroup>() : flow.groups;
            this.packageName = packageName;
            this.className = className;
            this.methodName = methodName;
            this.doForms = doForms;
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
                stepLines.add(as == null ? "" : "@LoginAs(" + quote(as) + ")");
                // its group and sentence cannot sit on an annotation: they are the first body lines
                for (FlowGroup g : groups) if (g.from == 0) addComment(g.say);
                addComment(steps.get(0).say);
                start = 1;
            }
            // a problem names the record as the list numbers it: "step 7" or "check 7.2"
            List<String> labels = StepNumbers.labels(steps);
            for (int i = start; i < steps.size(); i++) {
                String what = (StepNumbers.isCheck(steps.get(i)) ? "check " : "step ") + labels.get(i);
                Action a = steps.get(i);
                stepLines.add(null);
                if (a instanceof DoAction) {
                    doStep(i, (DoAction) a, what);
                    continue;
                }
                List<String> java = javaOf(a, what);
                if (java == null) continue;
                // a screenshot belongs to the block before it: it shows what those steps left on screen
                begin(i, a, a.say, a instanceof ExpectVerb);
                for (String line : java) statements.add(indent(line));
                stepLines.set(i, stepLine(a, java));
            }
        }

        /**
         * The Java lines of one step that is not a {@code do} step, its statement last (comments before it), or null when it
         * has none (the problem is recorded, named {@code what}).
         */
        private List<String> javaOf(Action a, String what) {
            if (a instanceof LoginAsAction) {
                problems.add(what + ": loginAs is only exportable as the first step");
                return null;
            }
            // The app steps the Client has a 1:1 form for; any other non-verb step is refused.
            if (a instanceof NavigateAction) {
                String target = ((NavigateAction) a).target;
                if (target == null || target.trim().isEmpty()) {
                    problems.add(what + ": navigate needs a target");
                    return null;
                }
                return Collections.singletonList("ui.navigate(" + quote(target) + ");");
            }
            if (a instanceof CloseAction) return Collections.singletonList("ui.closeActiveTab();");
            if (!(a instanceof VerbAction) || (a instanceof ContextMenuAction && ((ContextMenuAction) a).target == null)) {
                problems.add(what + ": " + a.describe() + " is not a step the Client API has a Java form for");
                return null;
            }

            VerbAction v = (VerbAction) a;
            if (hasBadLocator(v, what)) return null;

            String stmt = buildStatement(v, what);
            // null: a problem (e.g. a row with more than two keys) was already recorded; nothing to emit.
            if (stmt == null) return null;
            List<String> lines = new ArrayList<String>();
            if (v instanceof ScreenshotVerb && ((ScreenshotVerb) v).maxSize != null) {
                lines.add("// maxSize " + ((ScreenshotVerb) v).maxSize + " dropped");
            }
            if (Boolean.TRUE.equals(v.fragile)) lines.add("// " + fragile(v));
            lines.add(stmt);
            return lines;
        }

        /** The {@link #lines} entry of a step whose Java lines are {@code java}: its statement, a fragile one marked after it. */
        private String stepLine(Action a, List<String> java) {
            String stmt = java.get(java.size() - 1);
            return a instanceof VerbAction && Boolean.TRUE.equals(((VerbAction) a).fragile) ? stmt + "  // " + fragile((VerbAction) a) : stmt;
        }

        /** Why a fragile step is: its target is a point, or it needed nth. */
        private String fragile(VerbAction v) {
            for (Locator l : v.targets().values()) {
                if (l != null && l.point != null) return FRAGILE_POINT;
            }
            return FRAGILE;
        }

        /**
         * Writes do step {@code d} at record {@code i}: its typed call (under its {@code say} when that is not just its
         * sentence), or the steps it makes under {@code say}, else its sentence.
         */
        private void doStep(int i, DoAction d, String what) {
            DoForm f = doForms.of(d);
            if (f.leftOut) return;
            int before = problems.size();
            writeDoStep(i, d, what, f);
            // a recording keeps the step: what the writer refused in it is only the Java export's problem
            if (doForms.keeps()) problems.subList(before, problems.size()).clear();
        }

        private void writeDoStep(int i, DoAction d, String what, DoForm f) {
            if (f.refused != null) {
                problems.add(what + ": " + f.refused);
                return;
            }
            boolean check = StepNumbers.isCheck(d);
            String say = d.say == null || d.say.trim().isEmpty() ? null : d.say.trim();
            if (f.javaClass != null) {
                String stmt = typedCall(f, what);
                if (stmt == null) return;
                begin(i, d, say != null && !say.equals(f.sentence) ? say : null, check);
                statements.add(indent(stmt));
                stepLines.set(i, stmt);
                return;
            }
            List<String> lines = new ArrayList<String>();
            List<String> entries = new ArrayList<String>();
            boolean written = true;
            for (int k = 0; k < f.steps.size(); k++) {
                Action c = f.steps.get(k);
                String part = what + " (" + f.sentence + "), its step " + (k + 1);
                if (c instanceof DoAction) {
                    problems.add(part + ": a domain step's steps hold no do step");
                    written = false;
                    continue;
                }
                List<String> java = javaOf(c, part);
                if (java == null) {
                    written = false;
                    continue;
                }
                lines.addAll(java);
                entries.add(stepLine(c, java));
            }
            if (!written) return;
            begin(i, d, say != null ? say : f.sentence, check);
            for (String line : lines) statements.add(indent(line));
            stepLines.set(i, String.join("\n", entries));
        }

        /** {@code f}'s call with its values as Java literals, on its class; null (a problem recorded) when it names no param. */
        private String typedCall(DoForm f, String what) {
            Matcher m = PLACEHOLDER.matcher(f.call);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                String value = f.values.get(name);
                if (value == null) {
                    problems.add(what + ": the java call " + f.call + " names {" + name + "}, "
                            + (f.values.containsKey(name) ? "which has no value here" : "which is no param of the step"));
                    return null;
                }
                String literal = f.numbers.contains(name) ? String.valueOf(Integer.parseInt(value)) : quote(value);
                m.appendReplacement(out, Matcher.quoteReplacement(literal));
            }
            m.appendTail(out);
            String call = out.toString().trim();
            return classRef(f.javaClass) + "." + (call.endsWith(";") ? call : call + ";");
        }

        /** How a call names {@code className}: by its simple name, imported, unless the class already uses that name. */
        private String classRef(String className) {
            int dot = className.lastIndexOf('.');
            if (dot < 0) return className;   // the default package cannot be imported
            String simple = className.substring(dot + 1);
            String imported = doClasses.get(simple);
            if (imported == null && !TAKEN.contains(simple)) {
                doClasses.put(simple, className);
                return simple;
            }
            return className.equals(imported) ? simple : className;
        }

        /**
         * Opens the statement of record {@code i}: a blank line where checking turns back to acting and before a step with
         * a sentence or a group (never first, never twice), then the group's comment, then the sentence {@code say} (a
         * check's sentence goes right above its line). A screenshot joins the block before it. {@code checking} is null
         * before the first statement.
         */
        private void begin(int i, Action a, String say, boolean check) {
            boolean screenshot = a instanceof ScreenshotVerb;
            boolean groupStart = false;
            for (FlowGroup g : groups) if (g.from == i) groupStart = true;
            boolean hasSay = !check && say != null && !say.trim().isEmpty();
            boolean turn = !check && !screenshot && Boolean.TRUE.equals(checking);
            if (turn || groupStart || hasSay) blank();
            for (FlowGroup g : groups) if (g.from == i) addComment(g.say);
            addComment(say);
            checking = screenshot ? Boolean.valueOf(Boolean.TRUE.equals(checking)) : Boolean.valueOf(check);
        }

        private void blank() {
            if (!statements.isEmpty() && !statements.get(statements.size() - 1).isEmpty()) statements.add("");
        }

        private void addComment(String say) {
            if (say == null || say.trim().isEmpty()) return;
            statements.add(indent("// " + comment(say)));
        }

        /** True (and a problem recorded per bad locator) when any of this verb's targets uses an unconverted ref, or a point other than on a plain click. */
        private boolean hasBadLocator(VerbAction v, String what) {
            boolean bad = false;
            for (Locator l : v.targets().values()) {
                if (l == null) continue;
                List<String> uses = new ArrayList<String>();
                if (l.ref != null) uses.add("ref");
                if (l.point != null && !isPlainPointClick(v, l)) uses.add("point");
                if (uses.isEmpty()) continue;
                problems.add(what + ": locator " + l + " uses " + String.join("/", uses) + ", which a test cannot express");
                bad = true;
            }
            return bad;
        }

        /** A point has a Java form only as {@code clickAt(x, y)}: a plain left click, no count or modifiers, on this locator. */
        private static boolean isPlainPointClick(VerbAction v, Locator l) {
            if (!(v instanceof ClickVerb) || ((ClickVerb) v).target != l) return false;
            ClickVerb c = (ClickVerb) v;
            return (c.button == null || "left".equals(c.button)) && (c.count == null || c.count == 1)
                    && (c.modifiers == null || c.modifiers.isEmpty());
        }

        private String buildStatement(VerbAction v, String what) {
            if (v instanceof ClickVerb) return buildClick((ClickVerb) v, what);
            if (v instanceof TypeVerb) return buildType((TypeVerb) v, what);
            if (v instanceof PressVerb) return buildPress((PressVerb) v, what);
            if (v instanceof CheckVerb) return buildCheck((CheckVerb) v, what);
            if (v instanceof SelectVerb) return buildSelect((SelectVerb) v, what);
            if (v instanceof PickVerb) return buildPick((PickVerb) v);
            if (v instanceof MenuVerb) return buildMenu((MenuVerb) v);
            if (v instanceof AnswerVerb) return buildAnswer((AnswerVerb) v);
            if (v instanceof ChooseFileVerb) return buildChooseFile((ChooseFileVerb) v);
            if (v instanceof DragVerb) return buildDrag((DragVerb) v, what);
            if (v instanceof DropVerb) return buildDrop((DropVerb) v, what);
            if (v instanceof ScrollVerb) return buildScroll((ScrollVerb) v, what);
            if (v instanceof SetClipboardVerb) return clientRoot(v.timeout) + ".setClipboard(" + quote(((SetClipboardVerb) v).text) + ");";
            if (v instanceof ScreenshotVerb) return buildScreenshot((ScreenshotVerb) v, what);
            if (v instanceof ContextMenuAction) return buildContextMenu((ContextMenuAction) v, what);
            if (v instanceof ExpectVerb) return buildExpect((ExpectVerb) v, what);
            if (v instanceof HoverVerb) {
                HoverVerb h = (HoverVerb) v;
                String chain = actionChain(h.target, h.timeout, what);
                return chain == null ? null : chain + (Boolean.FALSE.equals(h.inside) ? ".unhover();" : ".hover();");
            }
            if (v instanceof ResizeColumnVerb) {
                ResizeColumnVerb r = (ResizeColumnVerb) v;
                String chain = actionChain(r.target, r.timeout, what);
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
            problems.add(what + ": " + v.describe() + " is a verb this writer has no Java form for");
            return null;
        }

        // ---- per-verb statements

        private String buildClick(ClickVerb v, String what) {
            String chain = actionChain(v.target, v.timeout, what);
            if (chain == null) return null;
            if (v.target.point != null) {
                // Only a plain click gets here with a point (hasBadLocator); the recorder marked it fragile.
                return chain + ".clickAt(" + v.target.point.get("x") + ", " + v.target.point.get("y") + ");";
            }
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

        private String buildType(TypeVerb v, String what) {
            String chain = actionChain(v.target, v.timeout, what);
            if (chain == null) return null;
            if (Boolean.TRUE.equals(v.replace) && Boolean.TRUE.equals(v.commit)) {
                return chain + ".fill(" + quote(v.text) + ");";
            }
            if (v.replace == null && v.commit == null) {
                return chain + ".type(" + quote(v.text) + ");";
            }
            return chain + ".type(" + quote(v.text) + ", " + boolLiteral(v.replace) + ", " + boolLiteral(v.commit) + ");";
        }

        private String buildPress(PressVerb v, String what) {
            if (v.target == null) {
                return clientRoot(v.timeout) + ".press(" + quote(v.keys) + ");";
            }
            String chain = actionChain(v.target, v.timeout, what);
            if (chain == null) return null;
            return chain + ".press(" + quote(v.keys) + ");";
        }

        private String buildCheck(CheckVerb v, String what) {
            String chain = actionChain(v.target, v.timeout, what);
            if (chain == null) return null;
            boolean on = v.checked == null || v.checked;
            return chain + (on ? ".check();" : ".uncheck();");
        }

        private String buildSelect(SelectVerb v, String what) {
            String chain = actionChain(v.target, v.timeout, what);
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
            String args = literal(v.path);
            if (v.approve != null) args += ", " + (v.approve ? "true" : "false");
            return clientRoot(v.timeout) + ".chooseFile(" + args + ");";
        }

        private String buildDrag(DragVerb v, String what) {
            String fromChain = actionChain(v.from, v.timeout, what);
            if (fromChain == null) return null;
            String toChain = chain(v.to, what);
            if (toChain == null) return null;
            return fromChain + ".dragTo(" + toChain + ");";
        }

        private String buildDrop(DropVerb v, String what) {
            String chain = actionChain(v.target, v.timeout, what);
            if (chain == null) return null;
            if (v.files != null) return chain + ".drop(" + joinQuoted(v.files) + ");";
            return chain + ".dropText(" + quote(v.text) + ");";
        }

        /** Exactly one of to/pages/row: a row scroll is the target's row chain plus {@code scrollIntoView()}. */
        private String buildScroll(ScrollVerb v, String what) {
            String chain = actionChain(v.target, v.timeout, what);
            if (chain == null) return null;
            if (v.to != null) return chain + ".scrollTo(" + quote(v.to) + ");";
            if (v.pages != null) return chain + ".scrollBy(" + v.pages + ");";
            return chain + ".scrollIntoView();";
        }

        private String buildScreenshot(ScreenshotVerb v, String what) {
            if (v.window == null && v.target == null) {
                return clientRoot(v.timeout) + ".saveScreenshot(" + quote(v.path) + scaleArg(v.scale) + ");";
            }
            if (v.target != null) {
                String chain = actionChain(v.target, v.timeout, what);
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

        private String buildContextMenu(ContextMenuAction v, String what) {
            String chain = actionChain(v.target, v.timeout, what);
            if (chain == null) return null;
            return chain + ".rightClick(" + joinQuoted(v.path) + ");";
        }

        private String buildExpect(ExpectVerb v, String what) {
            usesAssertThat = true;
            String chain = chain(v.target, what);
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
                String rowExpr = rowValueExpr(v.cell.row, what);
                if (rowExpr == null) return null;
                sb.append(".hasCell(").append(rowExpr).append(", ").append(quote(v.cell.column))
                        .append(", ").append(quote(v.cell.expected)).append(")");
            } else if (v.selected != null) {
                sb.append(".hasSelectedRows(").append(joinInts(v.selected)).append(")");
            } else if (v.shown != null) {
                sb.append(".isVisible()");
            } else if (v.absent != null) {
                sb.append(".isAbsent()");
            } else {
                problems.add(what + ": " + v.describe() + " has nothing to check");
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
        private String actionChain(Locator l, Long timeout, String what) {
            String c = chain(l, what);
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
        private String chain(Locator l, String what) {
            StringBuilder sb = new StringBuilder("ui");
            appendWindow(sb, l.window);
            if (l.scope != null) {
                for (String step : l.scope) appendScopeStep(sb, step);
            }
            appendLeaf(sb, l);
            if (l.item != null) sb.append(".item(").append(joinQuoted(l.item)).append(")");
            if (l.row != null) {
                String rowChain = rowChain(l.row, what);
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
            // A field chained into is Target's text scope (what it holds, such as its clear X).
            if (step.startsWith("text:")) { sb.append(".field(").append(quote(step.substring(5))).append(")"); return; }
            throw new IllegalStateException("scope step must be tab:<title>, section:<caption>, id:<name> or text:<name>, was '"
                    + step + "'");
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
        private String rowChain(Map<String, Object> row, String what) {
            if (isIndexRow(row)) return ".rowAt(" + intLiteral(row) + ")";
            if (row.size() == 1) {
                Map.Entry<String, Object> e = row.entrySet().iterator().next();
                return ".row(" + quote(e.getKey()) + ", " + valueLiteral(e.getValue()) + ")";
            }
            if (row.size() == 2) {
                usesRowsRow = true;
                return ".row(row(" + rowArgs(row) + "))";
            }
            problems.add(what + ": row with more than two keys");
            return null;
        }

        /** The value expression for a row used as an argument (e.g. {@code hasCell}'s row), not a chain call. */
        private String rowValueExpr(Map<String, Object> row, String what) {
            if (isIndexRow(row)) {
                usesRowsIndex = true;
                return "index(" + intLiteral(row) + ")";
            }
            if (row.size() == 1 || row.size() == 2) {
                usesRowsRow = true;
                return "row(" + rowArgs(row) + ")";
            }
            problems.add(what + ": row with more than two keys");
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

            Set<String> imports = new TreeSet<String>(doClasses.values());
            imports.add("io.github.kormcrete.swingui.junit.Client");
            if (loginAlias != null) imports.add("io.github.kormcrete.swingui.junit.LoginAs");
            imports.add("io.github.kormcrete.swingui.junit.UiTest");
            imports.add("org.junit.jupiter.api.Test");
            for (String type : imports) sb.append("import ").append(type).append(";\n");

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

        /**
         * A sentence as comment text: one line, and no backslash-u that javac would translate before it reads the comment
         * (a backslash is only eligible after an even run of backslashes, so a run before a u is made even).
         */
        static String comment(String say) {
            String oneLine = say.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
            StringBuilder sb = new StringBuilder();
            int run = 0;
            for (int k = 0; k < oneLine.length(); k++) {
                char c = oneLine.charAt(k);
                if (c == '\\') {
                    run++;
                } else {
                    if (c == 'u' && run % 2 == 1) sb.append('\\');
                    run = 0;
                }
                sb.append(c);
            }
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
