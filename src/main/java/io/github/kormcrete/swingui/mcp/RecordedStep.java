package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.flow.Action;

/**
 * One step of the session's recording: the {@link Action} plus the id it keeps for the whole recording and who made it.
 * The id is never reused; a position is only its place in the list right now.
 */
final class RecordedStep {

    /** Who made the step: the person at the palette, the agent, or a loaded recorded test. */
    enum Author {
        PERSON("person"), AGENT("agent"), LOADED("loaded");

        final String json;

        Author(String json) {
            this.json = json;
        }
    }

    /** A group of whole steps with one sentence; every record of its steps (actions and checks) shares the one object. */
    static final class Group {
        final int id;
        final String say;

        Group(int id, String say) {
            this.id = id;
            this.say = say;
        }
    }

    final int id;
    final Author author;
    final Action action;
    /** The step was changed after it was recorded (by either side). */
    final boolean edited;

    /** The group the step belongs to, or null. */
    final Group group;
    /** The row alternatives of a table step, once known; null otherwise. */
    final RowChoice row;

    RecordedStep(int id, Author author, Action action, boolean edited) {
        this(id, author, action, edited, null, null);
    }

    RecordedStep(int id, Author author, Action action, boolean edited, Group group, RowChoice row) {
        this.id = id;
        this.author = author;
        this.action = action;
        this.edited = edited;
        this.group = group;
        this.row = row;
    }

    /** The same step (id, author, group, row) with another action. */
    RecordedStep with(Action action, boolean edited) {
        return new RecordedStep(id, author, action, edited, group, row);
    }

    /** The same step in {@code group} (null = none). */
    RecordedStep inGroup(Group group) {
        return new RecordedStep(id, author, action, edited, group, row);
    }

    /** The same step with its row alternatives. */
    RecordedStep withRow(RowChoice row) {
        return new RecordedStep(id, author, action, edited, group, row);
    }
}
