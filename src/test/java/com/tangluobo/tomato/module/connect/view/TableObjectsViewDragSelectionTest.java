package com.tangluobo.tomato.module.connect.view;

import javafx.geometry.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableObjectsViewDragSelectionTest {

    @Test
    void usesVerticalRowWhenPointerIsRightOfTheLastColumn() {
        assertEquals(12, TableObjectsView.resolveDetailDragStartRow(-1, 12, 40));
        assertEquals(7, TableObjectsView.resolveDetailDragPointerRow(-1, 7));
    }

    @Test
    void usesLastRowOnlyWhenPointerIsBelowAllRows() {
        assertEquals(39, TableObjectsView.resolveDetailDragStartRow(-1, -1, 40));
        assertEquals(-1, TableObjectsView.resolveDetailDragStartRow(-1, -1, 0));
    }

    @Test
    void prefersDirectlyPickedCellRow() {
        assertEquals(5, TableObjectsView.resolveDetailDragStartRow(5, 12, 40));
        assertEquals(5, TableObjectsView.resolveDetailDragPointerRow(5, 12));
    }

    @Test
    void marqueeMustOverlapBothARowAndAColumn() {
        BoundingBox cell = new BoundingBox(0, 20, 600, 24);

        assertTrue(TableObjectsView.strictlyIntersects(
                new BoundingBox(500, 25, 200, 10), cell));
        assertFalse(TableObjectsView.strictlyIntersects(
                new BoundingBox(610, 25, 100, 10), cell));
        assertFalse(TableObjectsView.strictlyIntersects(
                new BoundingBox(500, 50, 100, 10), cell));
    }
}
