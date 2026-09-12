package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.markdown.editor.MarkdownTextArea;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractFileBrowserPaneShortcutTest {

    @Test
    void markdownEditorKeepsItsTextClipboardShortcuts() {
        assertTrue(AbstractFileBrowserPane.isTextEditingNodeType(MarkdownTextArea.class));
        assertTrue(AbstractFileBrowserPane.isTextEditingNodeType(TextArea.class));
    }

    @Test
    void ordinaryFileBrowserNodesStillUseFileClipboardShortcuts() {
        assertFalse(AbstractFileBrowserPane.isTextEditingTarget(new Pane()));
        assertFalse(AbstractFileBrowserPane.isTextEditingTarget(null));
    }
}
