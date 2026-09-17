package com.tangluobo.tomato.ssh;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalEmulatorTest {

    @Test
    void ctrlAAfterHistoryUsesTheDisplayedCommandStartAcrossOutputChunks() {
        TerminalEmulator emulator = new TerminalEmulator(120, 24);
        emulator.processString("[user@host ~]$ ");
        int commandStart = emulator.getCursorX();

        emulator.onUserInput();
        emulator.onShellHistoryNavigation();
        emulator.processString("echo recalled-history-command");

        emulator.onUserInput();
        emulator.onShellLineHome();
        emulator.processString("\r" + "\033[C".repeat(commandStart + 6));
        emulator.processString("\033[C\033[C\033[C");

        assertEquals(commandStart, emulator.getCursorX());
        assertEquals(0, emulator.getCursorY());

        emulator.onUserInput();
        emulator.processString("X");
        assertEquals('X', emulator.getChar(commandStart, 0));
        assertEquals(commandStart + 1, emulator.getCursorX());
    }

    @Test
    void cursorMovementIsUnchangedWithoutHistoryLineHomeHint() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24);
        emulator.processString("prompt$ command\r\033[12C");

        assertEquals(12, emulator.getCursorX());
    }

    @Test
    void lineHomeFallsBackToNormalRenderingWhenShellRedrawsTheLine() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24);
        String prompt = "prompt$ ";
        String history = "echo history";
        emulator.processString(prompt);
        emulator.onUserInput();
        emulator.onShellHistoryNavigation();
        emulator.processString(history);

        emulator.onUserInput();
        emulator.onShellLineHome();
        emulator.processString("\r" + prompt + history);

        assertEquals(prompt.length() + history.length(), emulator.getCursorX());
        assertEquals('p', emulator.getChar(0, 0));
    }

    @Test
    void preservesUtf8CharacterSplitAcrossNetworkReads() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24);
        byte[] chinese = "中".getBytes(StandardCharsets.UTF_8);

        emulator.process(chinese, 0, 1);
        assertEquals(0, emulator.getCursorX());
        emulator.process(chinese, 1, chinese.length - 1);

        assertEquals('中', emulator.getChar(0, 0));
        assertEquals('\0', emulator.getChar(1, 0));
        assertEquals(2, emulator.getCursorX());
    }

    @Test
    void predictsShellCursorAndConsumesReadlineEchoWithoutDoubleMovement() {
        TerminalEmulator emulator = shellWithInput("abc");
        int lineEnd = emulator.getCursorX();

        assertTrue(emulator.predictShellCursorMove(-1));
        assertEquals(lineEnd - 1, emulator.getCursorX());
        emulator.processString("\b");
        assertEquals(lineEnd - 1, emulator.getCursorX());

        assertTrue(emulator.predictShellCursorMove(1));
        assertEquals(lineEnd, emulator.getCursorX());
        emulator.processString("c");
        assertEquals(lineEnd, emulator.getCursorX());
        assertEquals('c', emulator.getChar(lineEnd - 1, 0));
    }

    @Test
    void keepsLatestPredictedPositionWhileRepeatedEchoesArrive() {
        TerminalEmulator emulator = shellWithInput("abcd");
        int lineEnd = emulator.getCursorX();

        assertTrue(emulator.predictShellCursorMove(-1));
        assertTrue(emulator.predictShellCursorMove(-1));
        assertEquals(lineEnd - 2, emulator.getCursorX());

        emulator.processString("\b");
        assertEquals(lineEnd - 2, emulator.getCursorX());
        emulator.processString("\033[D");
        assertEquals(lineEnd - 2, emulator.getCursorX());
    }

    @Test
    void keepsPredictionUntilOrderedEchoAfterFollowingCommandKey() {
        TerminalEmulator emulator = shellWithInput("abc");
        int lineEnd = emulator.getCursorX();

        assertTrue(emulator.predictShellCursorMove(-1));
        emulator.onShellCommandFinished();
        emulator.processString("\b");

        assertEquals(lineEnd - 1, emulator.getCursorX());
    }

    @Test
    void rollsBackPredictionBeforeUnexpectedRemoteOutput() {
        TerminalEmulator emulator = shellWithInput("abc");

        assertTrue(emulator.predictShellCursorMove(-1));
        emulator.processString("\r\nX");

        assertEquals(1, emulator.getCursorX());
        assertEquals(1, emulator.getCursorY());
        assertEquals('X', emulator.getChar(0, 1));
    }

    @Test
    void avoidsPredictionAtPromptBoundaryAndAcrossWideCharacters() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24);
        emulator.processString("$ ");
        emulator.onShellTextInput();
        assertFalse(emulator.predictShellCursorMove(-1));
        assertFalse(emulator.predictShellCursorMove(1));

        emulator.processString("中");
        assertFalse(emulator.predictShellCursorMove(-1));
    }

    private static TerminalEmulator shellWithInput(String input) {
        TerminalEmulator emulator = new TerminalEmulator(80, 24);
        emulator.processString("$ ");
        emulator.onShellTextInput();
        emulator.processString(input);
        return emulator;
    }
}
