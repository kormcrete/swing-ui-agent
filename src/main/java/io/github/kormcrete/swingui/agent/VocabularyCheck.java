package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import io.github.kormcrete.swingui.spi.Vocabulary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plugin's vocabulary as read when the plugin became ready: the steps that pass the checks {@link DomainStep}
 * describes, and one problem line per step dropped (or one for a vocabulary that failed). Match patterns are only
 * checked to be JSON objects here; what they say is the pattern compiler's to check.
 */
final class VocabularyCheck {

    static final VocabularyCheck NONE = new VocabularyCheck(Collections.<DomainStep>emptyList(), Collections.<String>emptyList());

    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern PARAM = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]\\w*)\\}");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    final List<DomainStep> steps;
    final List<String> problems;

    private VocabularyCheck(List<DomainStep> steps, List<String> problems) {
        this.steps = Collections.unmodifiableList(steps);
        this.problems = Collections.unmodifiableList(problems);
    }

    /** Reads {@code vocabulary} (plugin code: a throw is a problem, not a failure) and checks every step. */
    static VocabularyCheck of(String plugin, Vocabulary vocabulary) {
        if (vocabulary == null) return NONE;
        List<DomainStep> offered;
        try {
            offered = vocabulary.steps();
        } catch (RuntimeException | LinkageError e) {
            return new VocabularyCheck(Collections.<DomainStep>emptyList(),
                    Collections.singletonList(plugin + ": the vocabulary failed: " + e));
        }
        if (offered == null) return NONE;
        List<DomainStep> kept = new ArrayList<DomainStep>();
        List<String> problems = new ArrayList<String>();
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < offered.size(); i++) {
            DomainStep step = offered.get(i);
            if (step == null) {
                problems.add(plugin + ": entry " + (i + 1) + " of the vocabulary is null");
                continue;
            }
            List<String> why = check(step);
            if (!ids.add(step.id())) why.add("the id is already used by an earlier step of this plugin");
            if (why.isEmpty()) {
                kept.add(step);
            } else {
                problems.add(plugin + ": step '" + step.id() + "' dropped: " + String.join("; ", why));
            }
        }
        return new VocabularyCheck(kept, problems);
    }

    private static List<String> check(DomainStep step) {
        List<String> why = new ArrayList<String>();
        if (!ID.matcher(step.id()).matches()) {
            why.add("the id must start with a letter and hold only letters, digits, _ and -");
        }
        Set<String> declared = new LinkedHashSet<String>();
        for (DomainStep.Param p : step.params()) {
            if (!PARAM.matcher(p.name()).matches()) {
                why.add("param '" + p.name() + "': a name must start with a letter and hold only letters, digits and _");
            }
            if (p.optional() && p.type() == ParamType.INT && !isInt(p.defaultValue())) {
                why.add("the default of INT param '" + p.name() + "' is not a whole number in int range: '"
                        + p.defaultValue() + "'");
            }
            if (!declared.add(p.name())) why.add("param '" + p.name() + "' is declared twice");
        }
        if (step.sentence().trim().isEmpty()) why.add("the sentence is empty");
        undeclared("the sentence", step.sentence(), declared, why);
        if (step.javaClass() != null) {
            if (!CLASS_NAME.matcher(step.javaClass()).matches()) {
                why.add("the java class '" + step.javaClass() + "' is not a class name");
            }
            undeclared("the java call", step.javaCall(), declared, why);
        }
        boolean code = step.body() != null;
        boolean steps = !step.steps().isEmpty();
        if (!code && !steps) why.add("no body: give code(...) or steps(...)");
        if (code && steps) why.add("two bodies: give code(...) or steps(...), not both");
        for (int i = 0; i < step.steps().size(); i++) {
            String json = step.steps().get(i);
            String entry = "steps entry " + (i + 1);
            if (jsonObject(entry, json, why)) undeclared(entry, json, declared, why);
        }
        for (int i = 0; i < step.match().size(); i++) jsonObject("match entry " + (i + 1), step.match().get(i), why);
        for (int k = 0; k < step.matchAlso().size(); k++) {
            List<String> pattern = step.matchAlso().get(k);
            for (int i = 0; i < pattern.size(); i++) {
                jsonObject("matchAlso " + (k + 1) + " entry " + (i + 1), pattern.get(i), why);
            }
        }
        return why;
    }

    private static boolean isInt(String digits) {
        if (!digits.matches("\\d+")) return false;
        try {
            Integer.parseInt(digits);
            return true;
        } catch (NumberFormatException tooBig) {
            return false;
        }
    }

    /** Adds a problem per {@code {name}} in {@code text} that is no declared param (each name once). */
    private static void undeclared(String where, String text, Set<String> declared, List<String> why) {
        Set<String> seen = new HashSet<String>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!declared.contains(name) && seen.add(name)) {
                why.add(where + " names {" + name + "}, which is not a declared param");
            }
        }
    }

    private static boolean jsonObject(String where, String json, List<String> why) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            why.add(where + " is not valid JSON (" + e.getOriginalMessage() + ")");
            return false;
        }
        if (node == null || !node.isObject()) {
            why.add(where + " is not a JSON object");
            return false;
        }
        return true;
    }
}
