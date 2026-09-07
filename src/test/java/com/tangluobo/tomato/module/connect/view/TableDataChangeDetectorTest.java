package com.tangluobo.tomato.module.connect.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableDataChangeDetectorTest {

    @Test
    void unchangedRowsDoNotReportChanges() {
        assertAll(
                () -> assertFalse(TableDataChangeDetector.hasChanges(
                        List.of("1", "Alice"), List.of("1", "Alice"))),
                () -> assertFalse(TableDataChangeDetector.hasChanges(null, null)),
                () -> assertFalse(TableDataChangeDetector.hasChanges(List.of(), List.of())));
    }

    @Test
    void changingAndThenRestoringACellUpdatesDirtyState() {
        List<String> current = new ArrayList<>(List.of("1", "Alice"));
        List<String> original = List.copyOf(current);

        current.set(1, "Bob");
        assertTrue(TableDataChangeDetector.hasChanges(current, original));

        current.set(1, "Alice");
        assertFalse(TableDataChangeDetector.hasChanges(current, original));
    }

    @Test
    void nullAndEmptyStringAreDifferentCellValues() {
        assertAll(
                () -> assertTrue(TableDataChangeDetector.hasChanges(
                        Arrays.asList((String) null), List.of(""))),
                () -> assertTrue(TableDataChangeDetector.hasChanges(
                        List.of(""), Arrays.asList((String) null))));
    }

    @Test
    void emptyNewRowsHaveNoPersistableValue() {
        assertAll(
                () -> assertFalse(TableDataChangeDetector.hasPersistableValue(null)),
                () -> assertFalse(TableDataChangeDetector.hasPersistableValue(List.of())),
                () -> assertFalse(TableDataChangeDetector.hasPersistableValue(
                        Arrays.asList("", null, ""))));
    }

    @Test
    void filledNewRowsHaveAPersistableValue() {
        assertAll(
                () -> assertTrue(TableDataChangeDetector.hasPersistableValue(List.of("", "Alice"))),
                () -> assertTrue(TableDataChangeDetector.hasPersistableValue(List.of(" "))));
    }

    @Test
    void restrictedComparisonOnlyUsesEditableColumns() {
        List<String> original = List.of("1", "Alice", "generated");
        List<String> current = List.of("2", "Alice", "changed outside editable columns");

        assertAll(
                () -> assertFalse(TableDataChangeDetector.hasChanges(current, original, Set.of(1))),
                () -> assertTrue(TableDataChangeDetector.hasChanges(current, original, Set.of(0, 1))),
                () -> assertFalse(TableDataChangeDetector.hasChanges(current, original, Set.of())),
                () -> assertFalse(TableDataChangeDetector.hasChanges(current, original, null)));
    }

    @Test
    void reportsEveryChangedColumnIndexInColumnOrder() {
        List<String> current = Arrays.asList("1", "Bob", null, "extra");
        List<String> original = List.of("1", "Alice", "");

        assertEquals(List.of(1, 2, 3),
                List.copyOf(TableDataChangeDetector.changedColumnIndexes(current, original)));
    }
}
