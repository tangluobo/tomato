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

    @Test
    void detectsLanguageFromCommonDevelopmentFileNames() {
        assertEquals("java", IdeaCodeHighlighter.languageFromFileName("UserService.java"));
        assertEquals("typescript", IdeaCodeHighlighter.languageFromFileName("user-card.test.tsx"));
        assertEquals("kotlin", IdeaCodeHighlighter.languageFromFileName("build.gradle.kts"));
        assertEquals("groovy", IdeaCodeHighlighter.languageFromFileName("Jenkinsfile"));
        assertEquals("dockerfile", IdeaCodeHighlighter.languageFromFileName("Dockerfile.prod"));
        assertEquals("xml", IdeaCodeHighlighter.languageFromFileName("pom.xml"));
        assertEquals("plaintext", IdeaCodeHighlighter.languageFromFileName("README.md"));
        assertEquals("plaintext", IdeaCodeHighlighter.languageFromFileName("archive.bin"));
    }

    @Test
    void sourceLanguagesUseTheirOwnKeywords() {
        List<IdeaCodeHighlighter.Token> dart = IdeaCodeHighlighter.tokenize(
                "sealed class Account { final int id = 7; }", "dart");
        assertToken(dart, "sealed", IdeaCodeHighlighter.TokenType.KEYWORD);
        assertToken(dart, "final", IdeaCodeHighlighter.TokenType.KEYWORD);

        List<IdeaCodeHighlighter.Token> python = IdeaCodeHighlighter.tokenize(
                "async def load():\n    return None", "python");
        assertToken(python, "async", IdeaCodeHighlighter.TokenType.KEYWORD);
        assertToken(python, "def", IdeaCodeHighlighter.TokenType.KEYWORD);
        assertToken(python, "None", IdeaCodeHighlighter.TokenType.KEYWORD);
    }

    private static void assertToken(List<IdeaCodeHighlighter.Token> tokens, String text,
                                    IdeaCodeHighlighter.TokenType type) {
        assertTrue(tokens.stream().anyMatch(token -> token.text().equals(text) && token.type() == type),
                () -> "Missing token " + text + " as " + type + " in " + tokens);
    }
}
