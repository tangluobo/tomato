package com.tangluobo.tomato.module.connect.markdown.syntaxhighlighter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, dependency-free code tokenizer shared by the Markdown editor,
 * JavaFX preview and HTML export.  The palette mirrors IntelliJ IDEA's default
 * light scheme closely enough that the same token has the same colour in all
 * three places.
 */
public final class IdeaCodeHighlighter {

    public enum TokenType {
        BASE("code-base", "#080808"),
        KEYWORD("code-keyword", "#0033B3"),
        STRING("code-string", "#067D17"),
        COMMENT("code-comment", "#8C8C8C"),
        NUMBER("code-number", "#1750EB"),
        ANNOTATION("code-annotation", "#9E880D"),
        FUNCTION("code-function", "#00627A"),
        TAG("code-tag", "#0033B3"),
        ATTRIBUTE("code-attribute", "#174AD4");

        private final String cssClass;
        private final String color;

        TokenType(String cssClass, String color) {
            this.cssClass = cssClass;
            this.color = color;
        }

        public String cssClass() {
            return cssClass;
        }

        public String color() {
            return color;
        }
    }

    public record Token(String text, TokenType type) {
    }

    private record LanguagePattern(Pattern pattern, boolean blockComments, boolean tripleStrings) {
    }

    private static final Map<String, Set<String>> KEYWORDS = new HashMap<>();
    private static final Map<String, LanguagePattern> PATTERNS = new ConcurrentHashMap<>();
    private static final Set<String> COMMON_KEYWORDS = words(
            "if else for while do return break continue switch case default true false null class def func " +
            "function fn fun import package new this super self try catch finally throw throws public private " +
            "protected static const let var val void int string bool boolean float double");

    static {
        add("java", "abstract assert boolean break byte case catch char class const continue default do double " +
                "else enum extends final finally float for goto if implements import instanceof int interface " +
                "long native new package private protected public return short static strictfp super switch " +
                "synchronized this throw throws transient try void volatile while true false null var yield record " +
                "sealed permits non-sealed");
        add("kotlin", "as break class continue do else false for fun if in interface is null object package return " +
                "super this throw true try typealias val var when while by catch finally get import init out override " +
                "private protected public internal sealed data lateinit open abstract companion inline operator infix " +
                "crossinline suspend tailrec vararg reified");
        add("scala", "abstract case catch class def do else extends false final finally for if implicit import lazy " +
                "match new null object override package private protected return sealed super this throw trait try " +
                "true type val var while with yield given using enum export then");
        add("javascript", "break case catch class const continue debugger default delete do else export extends finally " +
                "for function if import in instanceof new return super switch this throw try typeof var void while " +
                "with yield let static true false null undefined async await of as");
        alias("js", "javascript");
        alias("jsx", "javascript");
        add("typescript", "break case catch class const continue debugger default delete do else export extends finally " +
                "for function if import in instanceof new return super switch this throw try typeof var void while " +
                "with yield let static true false null undefined async await of as interface type enum implements " +
                "private protected public readonly abstract is keyof infer namespace declare module symbol bigint " +
                "never unknown any satisfies");
        alias("ts", "typescript");
        alias("tsx", "typescript");
        add("python", "False None True and as assert async await break class continue def del elif else except finally " +
                "for from global if import in is lambda nonlocal not or pass raise return try while with yield print " +
                "match case self cls");
        alias("py", "python");
        add("sql", "select where insert update delete create table drop alter into values set join left right inner " +
                "outer full cross group by order having limit offset distinct primary key foreign references index " +
                "unique between like exists union all and or not in is as from on using with case when then else end " +
                "if begin commit rollback grant revoke database schema view trigger procedure function null true false " +
                "asc desc count sum avg min max over partition window returning");
        add("go", "break case chan const continue default defer else fallthrough for func go goto if import interface " +
                "map package range return select struct switch type var true false nil iota make len cap new append panic recover");
        alias("golang", "go");
        add("rust", "as break const continue crate else enum extern false fn for if impl in let loop match mod move " +
                "mut pub ref return self Self static struct super trait true type unsafe use where while async await " +
                "dyn union box macro yield");
        alias("rs", "rust");
        add("cpp", "alignas alignof and auto bool break case catch char class const constexpr consteval constinit " +
                "continue co_await co_return co_yield decltype default delete do double else enum explicit export " +
                "extern false float for friend goto if inline int long mutable namespace new noexcept nullptr operator " +
                "or private protected public register reinterpret_cast return short signed sizeof static static_cast " +
                "struct switch template this thread_local throw true try typedef typename union unsigned using virtual " +
                "void volatile while std size_t");
        alias("c++", "cpp");
        alias("cc", "cpp");
        alias("cxx", "cpp");
        add("c", "auto break case char const continue default do double else enum extern float for goto if inline int " +
                "long register restrict return short signed sizeof static struct switch typedef union unsigned void " +
                "volatile while NULL size_t");
        add("csharp", "abstract as base bool break byte case catch char checked class const continue decimal default " +
                "delegate do double else enum event explicit extern false finally fixed float for foreach goto if " +
                "implicit in int interface internal is lock long namespace new null object operator out override params " +
                "private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct " +
                "switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while " +
                "var async await yield get set record init required");
        alias("cs", "csharp");
        alias("c#", "csharp");
        add("php", "abstract and array as break callable case catch class clone const continue declare default die do " +
                "echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile eval exit extends final " +
                "finally fn for foreach function global goto if implements include include_once instanceof insteadof " +
                "interface isset list match namespace new or print private protected public require require_once return " +
                "static switch throw trait try unset use var while xor yield true false null");
        add("shell", "if then else elif fi for do done while until case esac in function return break continue exit " +
                "echo printf read local declare export unset source alias shift test true false cd pwd set trap");
        alias("sh", "shell");
        alias("bash", "shell");
        alias("zsh", "shell");
        add("powershell", "begin break catch class continue data define do dynamicparam else elseif end enum exit " +
                "filter finally for foreach from function if in param process return switch throw trap try until using " +
                "var while workflow true false null");
        alias("ps1", "powershell");
        add("ruby", "BEGIN END alias and begin break case class def defined do else elsif end ensure false for if in " +
                "module next nil not or redo rescue retry return self super then true undef unless until when while yield " +
                "require include extend");
        alias("rb", "ruby");
        add("swift", "associatedtype class deinit enum extension fileprivate func import init inout internal let open " +
                "operator private protocol public static struct subscript typealias var break case continue default " +
                "defer do else fallthrough for guard if in repeat return switch where while as Any catch false is nil " +
                "rethrows super self Self throw throws true try async await actor some any");
        add("css", "important inherit initial unset revert none auto block inline flex grid absolute relative fixed " +
                "sticky transparent currentColor var calc rgb rgba hsl hsla");
    }

    private IdeaCodeHighlighter() {
    }

    public static String languageFromInfo(String info) {
        if (info == null) return "";
        String value = info.trim();
        int whitespace = firstWhitespace(value);
        if (whitespace >= 0) value = value.substring(0, whitespace);
        if (value.startsWith("{.") && value.endsWith("}")) value = value.substring(2, value.length() - 1);
        else if (value.startsWith(".")) value = value.substring(1);
        if (value.startsWith("language-")) value = value.substring("language-".length());
        return normalizeLanguage(value);
    }

    public static String normalizeLanguage(String language) {
        if (language == null) return "";
        String lang = language.trim().toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "js", "jsx", "node" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "kt", "kts" -> "kotlin";
            case "rs" -> "rust";
            case "golang" -> "go";
            case "c++", "cc", "cxx" -> "cpp";
            case "cs", "c#" -> "csharp";
            case "rb" -> "ruby";
            case "sh", "bash", "zsh", "console" -> "shell";
            case "ps1" -> "powershell";
            case "yml" -> "yaml";
            case "htm", "xhtml", "svg", "mathml" -> "html";
            case "md", "mdown" -> "markdown";
            case "text", "txt", "plain", "plaintext" -> "plaintext";
            default -> lang;
        };
    }

    public static List<Token> tokenize(String code, String language) {
        if (code == null || code.isEmpty()) return List.of();
        String lang = normalizeLanguage(language);
        if (isMarkup(lang)) return tokenizeMarkup(code);

        LanguagePattern config = PATTERNS.computeIfAbsent(lang, IdeaCodeHighlighter::buildPattern);
        Set<String> keywords = KEYWORDS.getOrDefault(lang, COMMON_KEYWORDS);
        Matcher matcher = config.pattern().matcher(code);
        List<Token> result = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            add(result, code.substring(last, matcher.start()), TokenType.BASE);
            TokenType type;
            if (config.blockComments() && matcher.group("BLOCK") != null) {
                type = TokenType.COMMENT;
            } else if (config.tripleStrings() && matcher.group("TRIPLE") != null) {
                type = TokenType.STRING;
            } else if (matcher.group("STRING") != null) {
                type = TokenType.STRING;
            } else if (matcher.group("LINE") != null) {
                type = TokenType.COMMENT;
            } else if (matcher.group("NUMBER") != null) {
                type = TokenType.NUMBER;
            } else if (matcher.group("ANNOT") != null) {
                type = TokenType.ANNOTATION;
            } else {
                String word = matcher.group("IDENT");
                String lookup = "sql".equals(lang) ? word.toLowerCase(Locale.ROOT) : word;
                if (keywords.contains(lookup)) {
                    type = TokenType.KEYWORD;
                } else {
                    int next = matcher.end();
                    while (next < code.length() && (code.charAt(next) == ' ' || code.charAt(next) == '\t')) next++;
                    type = next < code.length() && code.charAt(next) == '(' ? TokenType.FUNCTION : TokenType.BASE;
                }
            }
            add(result, matcher.group(), type);
            last = matcher.end();
        }
        add(result, code.substring(last), TokenType.BASE);
        return List.copyOf(result);
    }

    private static LanguagePattern buildPattern(String lang) {
        boolean tripleStrings = "python".equals(lang);
        boolean blockComments = !Set.of("python", "ruby", "shell", "powershell", "yaml", "toml", "ini",
                "properties", "conf", "dockerfile", "makefile", "r", "plaintext", "markdown").contains(lang);
        String lineComment = switch (lang) {
            case "sql" -> "--[^\\n]*";
            case "php" -> "(?://|#)[^\\n]*";
            case "python", "ruby", "shell", "powershell", "yaml", "toml", "ini", "properties", "conf",
                    "dockerfile", "makefile", "r", "plaintext" -> "#[^\\n]*";
            default -> "//[^\\n]*";
        };
        StringBuilder regex = new StringBuilder();
        if (blockComments) regex.append("(?<BLOCK>/\\*[\\s\\S]*?\\*/)|");
        if (tripleStrings) regex.append("(?<TRIPLE>\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?''')|");
        regex.append("(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)")
                .append("|(?<LINE>").append(lineComment).append(')')
                .append("|(?<NUMBER>0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\b\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFdDuUlL]?\\b)")
                .append("|(?<ANNOT>@[A-Za-z_][A-Za-z0-9_]*)")
                .append("|(?<IDENT>[A-Za-z_$][A-Za-z0-9_$]*)");
        return new LanguagePattern(Pattern.compile(regex.toString()), blockComments, tripleStrings);
    }

    private static List<Token> tokenizeMarkup(String code) {
        Pattern pattern = Pattern.compile(
                "(?<COMMENT><!--[\\s\\S]*?-->)" +
                "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')" +
                "|(?<TAG></?[A-Za-z][A-Za-z0-9:_-]*|/?>)" +
                "|(?<ATTR>[A-Za-z_:][A-Za-z0-9:_.-]*(?=\\s*=))" +
                "|(?<ENTITY>&(?:#\\d+|#x[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]+);)");
        Matcher matcher = pattern.matcher(code);
        List<Token> result = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            add(result, code.substring(last, matcher.start()), TokenType.BASE);
            TokenType type = matcher.group("COMMENT") != null ? TokenType.COMMENT
                    : matcher.group("STRING") != null ? TokenType.STRING
                    : matcher.group("TAG") != null ? TokenType.TAG
                    : matcher.group("ATTR") != null ? TokenType.ATTRIBUTE
                    : TokenType.ANNOTATION;
            add(result, matcher.group(), type);
            last = matcher.end();
        }
        add(result, code.substring(last), TokenType.BASE);
        return List.copyOf(result);
    }

    private static boolean isMarkup(String lang) {
        return "html".equals(lang) || "xml".equals(lang);
    }

    private static void add(List<Token> tokens, String text, TokenType type) {
        if (text == null || text.isEmpty()) return;
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).type() == type) {
            Token previous = tokens.remove(tokens.size() - 1);
            tokens.add(new Token(previous.text() + text, type));
        } else {
            tokens.add(new Token(text, type));
        }
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    private static void add(String language, String keywords) {
        KEYWORDS.put(language, words(keywords));
    }

    private static void alias(String alias, String language) {
        KEYWORDS.put(alias, KEYWORDS.get(language));
    }

    private static Set<String> words(String value) {
        Set<String> result = new HashSet<>();
        for (String word : value.split("\\s+")) {
            if (!word.isEmpty()) result.add(word);
        }
        return Set.copyOf(result);
    }
}
