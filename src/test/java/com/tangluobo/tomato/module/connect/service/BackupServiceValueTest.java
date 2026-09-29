package com.tangluobo.tomato.module.connect.service;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupServiceValueTest {

    @Test
    void serializesJdbcTemporalValuesWithoutCallingUnsupportedToInstant() {
        assertEquals("'2026-09-29'", BackupService.toSqlLiteral(Date.valueOf("2026-09-29")));
        assertEquals("'08:09:10'", BackupService.toSqlLiteral(Time.valueOf("08:09:10")));
        assertEquals("'2026-09-29 08:09:10'",
                BackupService.toSqlLiteral(Timestamp.valueOf("2026-09-29 08:09:10")));
    }

    @Test
    void serializesJavaTimeValuesUsingDatabaseFriendlyFormats() {
        assertEquals("'2026-09-29'", BackupService.toSqlLiteral(LocalDate.of(2026, 9, 29)));
        assertEquals("'08:09:10'", BackupService.toSqlLiteral(LocalTime.of(8, 9, 10)));
        assertEquals("'2026-09-29 08:09:10'",
                BackupService.toSqlLiteral(LocalDateTime.of(2026, 9, 29, 8, 9, 10)));
    }

    @Test
    void preservesNullAndEscapesText() {
        assertEquals("NULL", BackupService.toSqlLiteral(null));
        assertEquals("'Tom\\'s check-in'", BackupService.toSqlLiteral("Tom's check-in"));
    }
}
