package com.tangluobo.tomato.module.connect.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlEditorDuplicateSupportTest {

    @Test
    void duplicatesSelectionImmediatelyAfterSelectionAndSelectsTheCopy() {
        SqlEditorDuplicateSupport.DuplicateEdit edit =
                SqlEditorDuplicateSupport.duplicate("select name", 0, 6, 6);

        assertAll(
                () -> assertEquals("selectselect name", apply("select name", edit)),
                () -> assertEquals(6, edit.selectionAnchor()),
                () -> assertEquals(12, edit.selectionCaret()));
    }

    @Test
    void duplicatesCurrentLineBelowAndKeepsTheCaretColumn() {
        String source = "select id\nfrom users\nwhere enabled = 1";
        SqlEditorDuplicateSupport.DuplicateEdit edit =
                SqlEditorDuplicateSupport.duplicate(source, 0, 0, 14);

        assertAll(
                () -> assertEquals("select id\nfrom users\nfrom users\nwhere enabled = 1", apply(source, edit)),
                () -> assertEquals(25, edit.selectionCaret()),
                () -> assertEquals(edit.selectionAnchor(), edit.selectionCaret()));
    }

    @Test
    void duplicatesLastLineAndEmptyDocumentWithANewLine() {
        SqlEditorDuplicateSupport.DuplicateEdit lastLine =
                SqlEditorDuplicateSupport.duplicate("select id", 0, 0, 3);
        SqlEditorDuplicateSupport.DuplicateEdit empty =
                SqlEditorDuplicateSupport.duplicate("", 0, 0, 0);

        assertAll(
                () -> assertEquals("select id\nselect id", apply("select id", lastLine)),
                () -> assertEquals(13, lastLine.selectionCaret()),
                () -> assertEquals("\n", apply("", empty)),
                () -> assertEquals(1, empty.selectionCaret()));
    }

    @Test
    void duplicatesAnEmptyFirstLineAtTheStartOfTheDocument() {
        SqlEditorDuplicateSupport.DuplicateEdit edit =
                SqlEditorDuplicateSupport.duplicate("\nselect 1", 0, 0, 0);

        assertAll(
                () -> assertEquals("\n\nselect 1", apply("\nselect 1", edit)),
                () -> assertEquals(1, edit.selectionCaret()));
    }

    @Test
    void preservesCrLfWhenDuplicatingALine() {
        String source = "select 1\r\nselect 2";
        SqlEditorDuplicateSupport.DuplicateEdit edit =
                SqlEditorDuplicateSupport.duplicate(source, 0, 0, 4);

        assertEquals("select 1\r\nselect 1\r\nselect 2", apply(source, edit));
    }

    private static String apply(String source, SqlEditorDuplicateSupport.DuplicateEdit edit) {
        return source.substring(0, edit.insertionIndex()) + edit.text()
                + source.substring(edit.insertionIndex());
    }
}
