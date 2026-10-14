package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.flow.Action;

/**
 * One step of the session's recording: the {@link Action} plus the id it keeps for the whole recording and who made it.
 * The id is never reused; a position is only its place in the list right now.
 */
final class RecordedStep {

    /** Who made the step: the person at the palette, the agent, or a loaded flow. */
    enum Author {
        PERSON("person"), AGENT("agent"), FLOW("flow");

        final String json;

        Author(String json) {
            this.json = json;
        }
    }

    final int id;
    final Author author;
    final Action action;
    /** The step was changed after it was recorded (by either side). */
    final boolean edited;

    RecordedStep(int id, Author author, Action action, boolean edited) {
        this.id = id;
        this.author = author;
        this.action = action;
        this.edited = edited;
    }

    /** The same step (id, author) with another action. */
    RecordedStep with(Action action, boolean edited) {
        return new RecordedStep(id, author, action, edited);
    }
}
