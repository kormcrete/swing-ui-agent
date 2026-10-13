package io.github.kormcrete.swingui.driver.verbs;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cell editor that is a panel around one editable text area is typed into through that text area; anything else
 * yields nothing and keeps the ordinary cell path.
 */
class CompositeCellEditorTextTest {

    @Test
    void aPanelHoldingOneEditableTextAreaYieldsIt() {
        JPanel editor = new JPanel();
        JTextArea area = new JTextArea();
        JPanel actions = new JPanel();
        actions.add(new JButton("Apply"));
        editor.add(area);
        editor.add(actions);

        assertThat(LiveVerbs.compositeEditorText(editor)).isSameAs(area);
    }

    @Test
    void aTextEditorOrNoUnambiguousTextAreaYieldsNothing() {
        assertThat(LiveVerbs.compositeEditorText(new JTextField())).isNull();
        assertThat(LiveVerbs.compositeEditorText(null)).isNull();

        JPanel none = new JPanel();
        none.add(new JButton("x"));
        assertThat(LiveVerbs.compositeEditorText(none)).isNull();

        JPanel two = new JPanel();
        two.add(new JTextArea());
        two.add(new JTextField());
        assertThat(LiveVerbs.compositeEditorText(two)).isNull();

        JPanel readOnly = new JPanel();
        JTextArea label = new JTextArea();
        label.setEditable(false);
        readOnly.add(label);
        assertThat(LiveVerbs.compositeEditorText(readOnly)).isNull();
    }

    @Test
    void aSpinnerEditorIsTypedIntoThroughItsTextField() {
        JSpinner spinner = new JSpinner();
        JTextComponent field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        assertThat(LiveVerbs.editorText(spinner)).isSameAs(field);
        JTextField plain = new JTextField();
        assertThat(LiveVerbs.editorText(plain)).isSameAs(plain);
        assertThat(LiveVerbs.editorText(null)).isNull();
    }
}
