package io.github.kormcrete.swingui.driver.model;

import java.util.ArrayList;
import java.util.List;

/** One snapshot node. Public fields; the MCP layer serialises it without its null fields. */
public final class Node {

    public String ref;
    public String role;
    /** Stable id of the contributed section this node wraps, or null - see {@link Section}. */
    public String id;
    public String name;
    public String value;
    /** The tooltip the element (or, for a cell, its rendered cell) shows on hover, HTML stripped; null when none. */
    public String tooltip;
    public List<String> state;
    public Boolean modal;
    public String blockedBy;
    public List<String> columns;
    public List<List<String>> rows;
    public Integer rowCount;
    public List<Integer> selected;
    public List<String> items;
    public List<String> expanded;
    public Boolean approx;
    public Boolean truncated;
    public Scroll scroll;
    public List<Node> children;

    /**
     * Where a scrollable element is scrolled to, in pixels. {@code x}/{@code y} are
     * the viewport's position, {@code width}/{@code height} what it shows, and {@code maxX}/{@code maxY}
     * the largest position it can take - 0 when the content fits, which is how "this does not scroll"
     * reads. Two reads of {@code y} settle "the table did not move vertically"; the {@code atTop} and
     * {@code atBottom} states settle the ends.
     */
    public static final class Scroll {
        public int x;
        public int y;
        public int width;
        public int height;
        public int maxX;
        public int maxY;
    }

    public Node() {}

    public Node(String role, String name) {
        this.role = role;
        this.name = name;
    }

    public void addState(String s) {
        if (state == null) state = new ArrayList<String>();
        if (!state.contains(s)) state.add(s);
    }

    public boolean hasState(String s) {
        return state != null && state.contains(s);
    }

    public void addChild(Node child) {
        if (children == null) children = new ArrayList<Node>();
        children.add(child);
    }

    /** The first node, depth first and this one included, with the role and name; a null name matches any. */
    public Node find(String role, String name) {
        if (role.equals(this.role) && (name == null || name.equals(this.name))) return this;
        if (children != null) {
            for (Node c : children) {
                Node hit = c.find(role, name);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * Strips the bulk from this subtree, in place, keeping every node, its ref, role, name, value and
     * state: table, list and tree rows and items go (their {@code columns} and {@code rowCount} stay, so
     * a table still says what it holds), and so do scroll geometry and expanded paths. What is left
     * says what is there and how to address it - {@code read} a table target for its columns and rows, or a cell,
     * when its content matters.
     */
    public Node compact() {
        rows = null;
        items = null;
        expanded = null;
        scroll = null;
        if (children != null) for (Node c : children) c.compact();
        return this;
    }

    public int count() {
        int n = 1;
        if (children != null) for (Node c : children) n += c.count();
        return n;
    }
}
