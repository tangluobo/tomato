package com.tangluobo.tomato.utils;

import javafx.scene.input.MouseButton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowSelectorDragSelectionTest {

    @Test
    void secondaryClickKeepsExistingMultiRowSelection() {
        assertFalse(RowSelectorDragSelection.shouldReplaceSelectionOnPress(
                MouseButton.SECONDARY, true));
    }

    @Test
    void secondaryClickOnUnselectedRowSelectsOnlyThatRow() {
        assertTrue(RowSelectorDragSelection.shouldReplaceSelectionOnPress(
                MouseButton.SECONDARY, false));
    }

    @Test
    void primaryClickStillReplacesSelection() {
        assertTrue(RowSelectorDragSelection.shouldReplaceSelectionOnPress(
                MouseButton.PRIMARY, true));
    }

    @Test
    void rowRangeWorksInBothDirectionsAndIncludesTheTarget() {
        assertArrayEquals(new int[]{3, 9}, RowSelectorDragSelection.rowRange(3, 8));
        assertArrayEquals(new int[]{3, 9}, RowSelectorDragSelection.rowRange(8, 3));
        assertArrayEquals(new int[]{5, 6}, RowSelectorDragSelection.rowRange(5, 5));
    }
}
