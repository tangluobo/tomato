package com.tangluobo.tomato.module.connect.markdown.syntaxhighlighter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaCodeHighlighterTest {

    @Test
    void javaTokensUseIdeaPaletteAndDoNotHighlightKeywordsInsideStringsOrComments() {
        List<IdeaCodeHighlighter.Token> tokens = IdeaCodeHighlighter.tokenize(
                "public int answer = 42; // return false\nString value = \"class\";", "java");

        assertToken(tokens, "public", IdeaCodeHighlighter.TokenType.KEYWORD);
        assertToken(tokens, "42", IdeaCodeHighlighter.TokenType.NUMBER);
        assertToken(tokens, "// return false", IdeaCodeHighlighter.TokenType.COMMENT);
        assertToken(tokens, "\"class\"", IdeaCodeHighlighter.TokenType.STRING);
        assertEquals("#0033B3", IdeaCodeHighlighter.TokenType.KEYWORD.color());
        assertEquals("#067D17", IdeaCodeHighlighter.TokenType.STRING.color());
    }

    @Test
    void aliasesAndCaseInsensitiveSqlKeywordsAreRecognized() {
        assertEquals("typescript", IdeaCodeHighlighter.languageFromInfo("tsx title=demo.tsx"));
        assertEquals("cpp", IdeaCodeHighlighter.languageFromInfo("c++"));
        assertEquals("java", IdeaCodeHighlighter.languageFromInfo("{.java}"));

        List<IdeaCodeHighlighter.Token> sql = IdeaCodeHighlighter.tokenize("SELECT count(*) FROM users", "sql");
        assertToken(sql, "SELECT", IdeaCodeHighlighter.TokenType.KEYWORD);
        assertToken(sql, "count", IdeaCodeHighlighter.TokenType.KEYWORD);
        assertToken(sql, "FROM", IdeaCodeHighlighter.TokenType.KEYWORD);
    }

    @Test
    void htmlTagsAttributesStringsAndCommentsHaveDifferentStyles() {
        List<IdeaCodeHighlighter.Token> tokens = IdeaCodeHighlighter.tokenize(
                "<!-- note --><img src=\"demo.png\" alt='demo'>", "html");

        assertToken(tokens, "<!-- note -->", IdeaCodeHighlighter.TokenType.COMMENT);
        assertToken(tokens, "<img", IdeaCodeHighlighter.TokenType.TAG);
        assertToken(tokens, "src", IdeaCodeHighlighter.TokenType.ATTRIBUTE);
        assertToken(tokens, "\"demo.png\"", IdeaCodeHighlighter.TokenType.STRING);
    }

    private static void assertToken(List<IdeaCodeHighlighter.Token> tokens, String text,
                                    IdeaCodeHighlighter.TokenType type) {
        assertTrue(tokens.stream().anyMatch(token -> token.text().equals(text) && token.type() == type),
                () -> "Missing token " + text + " as " + type + " in " + tokens);
    }
}
