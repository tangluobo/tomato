package com.tangluobo.tomato.module.connect.dialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupDialogTest {

    @Test
    void describesExceptionsThatHaveNoMessage() {
        assertEquals("UnsupportedOperationException",
                BackupDialog.describeFailure(new UnsupportedOperationException()));
        assertEquals("底层失败",
                BackupDialog.describeFailure(new RuntimeException(null, new IllegalStateException("底层失败"))));
        assertEquals("未知错误", BackupDialog.describeFailure(null));
    }
}
