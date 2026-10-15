package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.flow.verb.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base type for all steps. The JSON discriminator is the {@code action} property: one of the driver
 * verbs ({@link io.github.kormcrete.swingui.flow.verb.VerbAction} subtypes, {@code contextMenu} included), or one
 * of the three app steps {@code navigate}, {@code close}, {@code loginAs}. Any other name is BAD_INPUT
 * ({@link FlowJson#unknownStepType}).
 *
 * <p>{@code @JsonInclude} declared here is inherited by every subclass that does not declare its own.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClickVerb.class, name = "click"),
        @JsonSubTypes.Type(value = TypeVerb.class, name = "type"),
        @JsonSubTypes.Type(value = PressVerb.class, name = "press"),
        @JsonSubTypes.Type(value = CheckVerb.class, name = "check"),
        @JsonSubTypes.Type(value = SelectVerb.class, name = "select"),
        @JsonSubTypes.Type(value = PickVerb.class, name = "pick"),
        @JsonSubTypes.Type(value = MenuVerb.class, name = "menu"),
        @JsonSubTypes.Type(value = AnswerVerb.class, name = "answer"),
        @JsonSubTypes.Type(value = ChooseFileVerb.class, name = "chooseFile"),
        @JsonSubTypes.Type(value = DragVerb.class, name = "drag"),
        @JsonSubTypes.Type(value = DropVerb.class, name = "drop"),
        @JsonSubTypes.Type(value = ExpectVerb.class, name = "expect"),
        @JsonSubTypes.Type(value = ScrollVerb.class, name = "scroll"),
        @JsonSubTypes.Type(value = SetClipboardVerb.class, name = "setClipboard"),
        @JsonSubTypes.Type(value = ScreenshotVerb.class, name = "screenshot"),
        @JsonSubTypes.Type(value = ContextMenuAction.class, name = "contextMenu"),
        @JsonSubTypes.Type(value = HoverVerb.class, name = "hover"),
        @JsonSubTypes.Type(value = ResizeColumnVerb.class, name = "resizeColumn"),
        @JsonSubTypes.Type(value = ResizeWindowVerb.class, name = "resizeWindow"),
        @JsonSubTypes.Type(value = CloseWindowVerb.class, name = "closeWindow"),
        @JsonSubTypes.Type(value = FocusFileChooserNameVerb.class, name = "focusFileChooserName"),
        @JsonSubTypes.Type(value = NavigateAction.class, name = "navigate"),
        @JsonSubTypes.Type(value = CloseAction.class, name = "close"),
        @JsonSubTypes.Type(value = LoginAsAction.class, name = "loginAs")
})
public abstract class Action {
    /** Short human label for logs/results, e.g. "navigate(Orders)". */
    public abstract String describe();
}
