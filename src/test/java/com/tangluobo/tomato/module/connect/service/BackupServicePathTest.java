package com.tangluobo.tomato.module.connect.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServicePathTest {

    @Test
    void traversalSegmentsStayInsideDatabaseBackupDirectory() {
        Path root = BackupService.resolveBackupDir("connection", "database", "");
        Path traversal = BackupService.resolveBackupDir("connection", "database", "../outside");

        assertTrue(traversal.startsWith(root));
        assertEquals(root.resolve("unnamed").resolve("outside"), traversal);
        assertFalse(traversal.toString().contains(".."));
    }

    @Test
    void dotOnlyConnectionAndDatabaseNamesCannotSelectParentDirectories() {
        Path directory = BackupService.resolveBackupDir("..", ".", null);

        assertEquals("backup", directory.getFileName().toString());
        assertEquals("unnamed", directory.getParent().getFileName().toString());
        assertEquals("unnamed", directory.getParent().getParent().getFileName().toString());
    }
}
