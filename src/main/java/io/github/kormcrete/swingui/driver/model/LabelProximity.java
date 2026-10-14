package io.github.kormcrete.swingui.driver.model;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relates labels and inputs the way the eye does. Prefers {@link JLabel#getLabelFor()}; falls back to
 * geometry (a caption stacked over its panel's only input, else the nearest input, same row to the right
 * preferred). Inputs are text components and combo
 * boxes. Only labels and inputs {@link Visibility} accepts take part. Ambiguity tells the author to
 * add {@code setLabelFor(field)} in source.
 */
public final class LabelProximity {

    private LabelProximity() {}

    public static JTextComponent findTextInputFor(Container root, String label) {
        return findTextInputFor(root, label, Visibility.SHOWING);
    }

    public static JTextComponent findTextInputFor(Container root, String label, Visibility visibility) {
        Component input = findInputFor(root, label, visibility);
        if (input instanceof JTextComponent) {
            return (JTextComponent) input;
        }
        throw new RuntimeException("field labeled '" + label + "' is not a text input ("
                + input.getClass().getSimpleName() + ")");
    }

    public static Component findInputFor(Container root, String label) {
        return findInputFor(root, label, Visibility.SHOWING);
    }

    public static Component findInputFor(Container root, String label, Visibility visibility) {
        String target = normalize(label);
        List<JLabel> matches = new ArrayList<JLabel>();
        for (JLabel l : labels(root, visibility)) {
            if (normalize(l.getText()).equals(target)) matches.add(l);
        }
        if (matches.isEmpty()) {
            throw new RuntimeException("no field labeled '" + label + "'");
        }
        if (matches.size() > 1) {
            throw new RuntimeException("ambiguous label '" + label
                    + "' — add setLabelFor(field) to the intended label in source");
        }
        JLabel lbl = matches.get(0);
        Component linked = lbl.getLabelFor();
        if (linked instanceof JTextComponent || linked instanceof JComboBox) {
            return linked;
        }
        if (linked != null) {
            throw new RuntimeException("unsupported field type for label '" + label + "': "
                    + linked.getClass().getSimpleName() + " (supports text inputs and dropdowns)");
        }
        Component own = captionAbove(lbl, visibility);
        if (own != null) {
            return own;
        }
        Component best = nearest(lbl, inputs(root, visibility));
        if (best == null) {
            throw new RuntimeException("could not resolve an input for label '" + label
                    + "' — add setLabelFor(field) in source");
        }
        return best;
    }

    /**
     * Which label names which component: labelFor links first, then stacked captions; then each other label
     * names the input it sits next to, and when two labels pick the same input the closer one wins.
     */
    public static Map<Component, JLabel> labelIndex(Container root, Visibility visibility) {
        List<JLabel> labels = labels(root, visibility);
        Map<Component, JLabel> out = new IdentityHashMap<Component, JLabel>();
        for (JLabel l : labels) {
            Component linked = l.getLabelFor();
            if (linked != null && !out.containsKey(linked)) out.put(linked, l);
        }
        Set<JLabel> stacked = Collections.newSetFromMap(new IdentityHashMap<JLabel, Boolean>());
        for (JLabel l : labels) {
            if (l.getLabelFor() != null) continue;
            Component own = captionAbove(l, visibility);
            if (own != null && !out.containsKey(own)) {
                out.put(own, l);
                stacked.add(l);
            }
        }
        List<Component> inputs = inputs(root, visibility);
        Map<Component, Double> distances = new IdentityHashMap<Component, Double>();
        for (JLabel l : labels) {
            if (l.getLabelFor() != null || stacked.contains(l)) continue;
            Component input = nearest(l, inputs);
            if (input == null) continue;
            if (out.containsKey(input) && !distances.containsKey(input)) continue;
            double d = distance(l, input);
            Double best = distances.get(input);
            if (best == null || d < best) {
                out.put(input, l);
                distances.put(input, d);
            }
        }
        return out;
    }

    private static List<JLabel> labels(Container root, Visibility visibility) {
        List<JLabel> out = new ArrayList<JLabel>();
        collect(root, JLabel.class, out, visibility);
        List<JLabel> named = new ArrayList<JLabel>();
        for (JLabel l : out) {
            if (!normalize(l.getText()).isEmpty()) named.add(l);
        }
        return named;
    }

    private static List<Component> inputs(Container root, Visibility visibility) {
        List<Component> out = new ArrayList<Component>();
        collect(root, JTextComponent.class, out, visibility);
        collect(root, JComboBox.class, out, visibility);
        return out;
    }

    private static <T> void collect(Component c, Class<T> type, List<? super T> out, Visibility visibility) {
        if (!c.isVisible() || Roles.agentOwn(c)) return;
        if (type.isInstance(c) && visibility.isVisible(c)) {
            out.add(type.cast(c));
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collect(child, type, out, visibility);
            }
        }
    }

    /**
     * Nearest input to the label: same-row inputs to the right win (closest first); otherwise the
     * geometrically closest. Positions are summed up the parent chain, so this works without a window.
     */
    private static Component nearest(JLabel label, List<Component> inputs) {
        Component sameRow = sameRowRight(label, inputs);
        if (sameRow != null) return sameRow;
        Point lp = absolute(label);
        Component bestOther = null;
        double bestOtherDist = Double.MAX_VALUE;
        for (Component in : inputs) {
            Point ip = absolute(in);
            double dist = Math.hypot(ip.x - lp.x, ip.y - lp.y);
            if (dist < bestOtherDist) { bestOtherDist = dist; bestOther = in; }
        }
        return bestOther;
    }

    /** The closest input on the label's own row and to its right: a caption and its field. */
    private static Component sameRowRight(JLabel label, List<Component> inputs) {
        Point lp = absolute(label);
        int labelMidY = lp.y + label.getBounds().height / 2;
        Component best = null;
        double bestDx = Double.MAX_VALUE;
        for (Component in : inputs) {
            Point ip = absolute(in);
            boolean sameRow = Math.abs((ip.y + in.getHeight() / 2) - labelMidY) <= Math.max(8, in.getHeight());
            if (!sameRow || ip.x < lp.x) continue;
            double dx = ip.x - lp.x;
            if (dx < bestDx) { bestDx = dx; best = in; }
        }
        return best;
    }

    /**
     * The labels that read as a field's caption: linked by {@code setLabelFor} to a text input or combo box,
     * stacked above their panel's only input, or on their input's row to its left (closest wins when two compete).
     *
     * <p>Narrower than {@link #labelIndex}, whose closest-input fallback makes every label name something
     * once the screen holds one input. A count strip three rows below the last field is not its caption.
     */
    public static Set<JLabel> captionLabels(Container root, Visibility visibility) {
        Set<JLabel> out = Collections.newSetFromMap(new IdentityHashMap<JLabel, Boolean>());
        List<Component> inputs = inputs(root, visibility);
        Map<Component, JLabel> winner = new IdentityHashMap<Component, JLabel>();
        Map<Component, Double> best = new IdentityHashMap<Component, Double>();
        Set<Component> stackedInputs = Collections.newSetFromMap(new IdentityHashMap<Component, Boolean>());
        List<JLabel> labels = labels(root, visibility);
        for (JLabel l : labels) {
            if (l.getLabelFor() != null) continue;
            Component own = captionAbove(l, visibility);
            if (own != null && stackedInputs.add(own)) out.add(l);
        }
        for (JLabel l : labels) {
            Component linked = l.getLabelFor();
            if (linked instanceof JTextComponent || linked instanceof JComboBox) {
                out.add(l);
                continue;
            }
            if (linked != null || out.contains(l)) continue;
            Component input = sameRowRight(l, inputs);
            if (input == null || stackedInputs.contains(input)) continue;
            if (input == null) continue;
            double d = distance(l, input);
            Double b = best.get(input);
            if (b == null || d < b) {
                winner.put(input, l);
                best.put(input, d);
            }
        }
        out.addAll(winner.values());
        return out;
    }

    /**
     * The input {@code label} heads as a caption stacked above it: the label and exactly one input are the only label
     * and the only input among the visible children of their panel, the label above the input, the two overlapping
     * horizontally — a figure cell, caption over value. Null otherwise. The same-row rule mispairs these: the
     * caption counts as on its value's row, and when the value starts a pixel to its left, the rule picks the
     * neighbouring cell's value.
     */
    static Component captionAbove(JLabel label, Visibility visibility) {
        Container panel = label.getParent();
        if (panel == null) return null;
        Component input = null;
        int labelCount = 0;
        for (Component c : panel.getComponents()) {
            if (!c.isVisible() || !visibility.isVisible(c)) continue;
            if (c instanceof JLabel) labelCount++;
            else if (c instanceof JTextComponent || c instanceof JComboBox) {
                if (input != null) return null;
                input = c;
            }
        }
        if (input == null || labelCount != 1) return null;
        Rectangle l = label.getBounds();
        Rectangle i = input.getBounds();
        boolean above = l.y + l.height <= i.y + 2;
        boolean overlaps = l.x < i.x + i.width && i.x < l.x + l.width;
        return above && overlaps ? input : null;
    }

    private static double distance(Component a, Component b) {
        Point pa = absolute(a);
        Point pb = absolute(b);
        return Math.hypot(pa.x - pb.x, pa.y - pb.y);
    }

    private static Point absolute(Component c) {
        int x = 0, y = 0;
        for (Component cur = c; cur != null; cur = cur.getParent()) {
            Rectangle b = cur.getBounds();
            x += b.x;
            y += b.y;
        }
        return new Point(x, y);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.endsWith(":")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }
}
