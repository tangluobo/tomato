package com.tangluobo.tomato.module.connect.view;

/**
 * Computes the text edit used by SQL editor duplicate actions. Keeping the
 * calculation independent from JavaFX makes keyboard and mouse duplication
 * share exactly the same selection/caret rules.
 */
final class SqlEditorDuplicateSupport {

    private SqlEditorDuplicateSupport() {
    }

    static DuplicateEdit duplicate(String source, int selectionStart, int selectionEnd, int caretPosition) {
        String text = source == null ? "" : source;
        int length = text.length();
        int start = clamp(Math.min(selectionStart, selectionEnd), 0, length);
        int end = clamp(Math.max(selectionStart, selectionEnd), start, length);

        if (start < end) {
            String selectedText = text.substring(start, end);
            return new DuplicateEdit(end, selectedText, end, end + selectedText.length());
        }

        int caret = clamp(caretPosition, 0, length);
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        int lineFeed = text.indexOf('\n', caret);
        int lineEnd = lineFeed >= 0 ? lineFeed : length;
        int contentEnd = lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r'
                ? lineEnd - 1 : lineEnd;
        String line = text.substring(lineStart, contentEnd);
        int column = clamp(caret - lineStart, 0, line.length());

        if (lineFeed >= 0) {
            String separator = contentEnd < lineEnd ? "\r\n" : "\n";
            int insertion = lineFeed + 1;
            return new DuplicateEdit(insertion, line + separator,
                    insertion + column, insertion + column);
        }

        String separator = text.contains("\r\n") ? "\r\n" : "\n";
        int insertion = length;
        int duplicatedLineStart = insertion + separator.length();
        return new DuplicateEdit(insertion, separator + line,
                duplicatedLineStart + column, duplicatedLineStart + column);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    record DuplicateEdit(int insertionIndex, String text, int selectionAnchor, int selectionCaret) {
    }
}
