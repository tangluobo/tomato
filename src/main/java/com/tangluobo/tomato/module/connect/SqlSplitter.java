package com.tangluobo.tomato.module.connect;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL语句拆分工具类
 * 使用有限状态机逐字符扫描，正确处理字符串和注释中的分号
 */
public class SqlSplitter {

    private enum State {
        NORMAL,
        IN_SINGLE_QUOTE,
        IN_DOUBLE_QUOTE,
        IN_LINE_COMMENT,
        IN_BLOCK_COMMENT
    }

    /**
     * 将SQL文本拆分为独立的可执行语句
     * 正确处理字符串字面量、单行注释(--)、多行注释()中包含的分号
     *
     * @param sql 输入的SQL文本，可能包含多条语句
     * @return 拆分后的语句列表，每条语句去除首尾空白
     */
    public static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return statements;
        }

        State state = State.NORMAL;
        StringBuilder current = new StringBuilder();
        int len = sql.length();

        for (int i = 0; i < len; i++) {
            char ch = sql.charAt(i);
            char next = (i + 1 < len) ? sql.charAt(i + 1) : '\0';

            switch (state) {
                case NORMAL:
                    if (ch == '\'') {
                        current.append(ch);
                        state = State.IN_SINGLE_QUOTE;
                    } else if (ch == '"') {
                        current.append(ch);
                        state = State.IN_DOUBLE_QUOTE;
                    } else if (ch == '-' && next == '-') {
                        current.append(ch).append(next);
                        i++; // 跳过下一个 '-'
                        state = State.IN_LINE_COMMENT;
                    } else if (ch == '/' && next == '*') {
                        current.append(ch).append(next);
                        i++; // 跳过 '*'
                        state = State.IN_BLOCK_COMMENT;
                    } else if (ch == ';') {
                        // 分号在普通代码区，截取为一条语句
                        String stmt = current.toString().trim();
                        if (!stmt.isEmpty()) {
                            statements.add(stmt);
                        }
                        current.setLength(0);
                    } else {
                        current.append(ch);
                    }
                    break;

                case IN_SINGLE_QUOTE:
                    current.append(ch);
                    if (ch == '\'') {
                        // SQL标准：连续两个单引号表示转义的单引号
                        if (next == '\'') {
                            current.append(next);
                            i++; // 跳过转义引号
                        } else {
                            state = State.NORMAL;
                        }
                    }
                    break;

                case IN_DOUBLE_QUOTE:
                    current.append(ch);
                    if (ch == '"') {
                        if (next == '"') {
                            current.append(next);
                            i++; // 跳过转义引号
                        } else {
                            state = State.NORMAL;
                        }
                    }
                    break;

                case IN_LINE_COMMENT:
                    current.append(ch);
                    if (ch == '\n') {
                        state = State.NORMAL;
                    }
                    break;

                case IN_BLOCK_COMMENT:
                    current.append(ch);
                    if (ch == '*' && next == '/') {
                        current.append(next);
                        i++; // 跳过 '/'
                        state = State.NORMAL;
                    }
                    break;
            }
        }

        // 处理最后一条语句（无分号结尾）
        String lastStmt = current.toString().trim();
        if (!lastStmt.isEmpty()) {
            statements.add(lastStmt);
        }

        return statements;
    }

    /**
     * 判断SQL语句是否为SELECT类语句（可能返回结果集）
     * 去除前导注释和空白后，检查是否以SELECT/WITH/SHOW/DESCRIBE/EXPLAIN开头
     *
     * @param sql SQL语句
     * @return 是否为SELECT类语句
     */
    public static boolean isSelectStatement(String sql) {
        if (sql == null || sql.isEmpty()) return false;

        // 去除前导注释和空白
        String trimmed = stripLeadingComments(sql).trim().toUpperCase();

        return trimmed.startsWith("SELECT")
                || trimmed.startsWith("WITH")
                || trimmed.startsWith("SHOW")
                || trimmed.startsWith("DESCRIBE")
                || trimmed.startsWith("DESC")
                || trimmed.startsWith("EXPLAIN");
    }

    /**
     * 从简单的SELECT语句中提取源表名
     * 仅处理 SELECT ... FROM table_name 形式的单表查询
     * 复杂查询（多表JOIN、子查询等）返回null
     *
     * @param sql SQL语句
     * @return 表名，若无法确定则返回null
     */
    public static String extractTableName(String sql) {
        if (sql == null || sql.isEmpty()) return null;

        String trimmed = stripLeadingComments(sql).trim();
        String upper = trimmed.toUpperCase();

        // 只处理SELECT语句
        if (!upper.startsWith("SELECT")) return null;

        // 查找FROM关键字（不在引号或子查询中）
        int fromIndex = findTopLevelFrom(trimmed);
        if (fromIndex < 0) return null;

        // FROM之后的表名
        String afterFrom = trimmed.substring(fromIndex + 4).trim();
        // 查询结果允许回写时必须能唯一确定源表；JOIN 或逗号多表查询一律保持只读。
        if (hasTopLevelMultipleSources(afterFrom)) return null;

        // 去掉可能的别名前的AS
        // 提取第一个标识符（表名）
        StringBuilder tableName = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = '\0';

        for (int i = 0; i < afterFrom.length(); i++) {
            char ch = afterFrom.charAt(i);

            if (!inQuote) {
                if (ch == '`' || ch == '"' || ch == '[') {
                    inQuote = true;
                    quoteChar = ch;
                    continue;
                }
                if (Character.isWhitespace(ch) || ch == ',' || ch == ';' || ch == '(' || ch == ')') {
                    break;
                }
                // 如果遇到WHERE/JOIN/LIMIT/ORDER/GROUP/HAVING等关键字，停止
                if (i > 0 && Character.isLetter(ch)) {
                    String rest = afterFrom.substring(i).toUpperCase();
                    if (rest.startsWith("WHERE") || rest.startsWith("JOIN") || rest.startsWith("INNER")
                        || rest.startsWith("LEFT") || rest.startsWith("RIGHT") || rest.startsWith("LIMIT")
                        || rest.startsWith("ORDER") || rest.startsWith("GROUP") || rest.startsWith("HAVING")
                        || rest.startsWith("UNION") || rest.startsWith("EXCEPT") || rest.startsWith("INTERSECT")
                        || rest.startsWith("FOR") || rest.startsWith("AS")) {
                        break;
                    }
                }
                tableName.append(ch);
            } else {
                if ((ch == '`' && quoteChar == '`') || (ch == '"' && quoteChar == '"')
                    || (ch == ']' && quoteChar == '[')) {
                    inQuote = false;
                    continue;
                }
                tableName.append(ch);
            }
        }

        String result = tableName.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** 检测 FROM 子句顶层的 JOIN/逗号，忽略字符串、引号标识符和括号内部内容。 */
    private static boolean hasTopLevelMultipleSources(String fromClause) {
        int depth = 0;
        char quote = '\0';
        for (int i = 0; i < fromClause.length(); i++) {
            char ch = fromClause.charAt(i);
            if (quote != '\0') {
                if ((quote == '[' && ch == ']') || (quote != '[' && ch == quote)) {
                    quote = '\0';
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                if (depth > 0) depth--;
                continue;
            }
            if (depth != 0) continue;
            if (ch == ',') return true;
            if (matchesKeywordAt(fromClause, i, "JOIN")) return true;
            if (matchesAnyKeywordAt(fromClause, i,
                    "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET",
                    "UNION", "EXCEPT", "INTERSECT", "FOR")) {
                return false;
            }
        }
        return false;
    }

    private static boolean matchesAnyKeywordAt(String text, int index, String... keywords) {
        for (String keyword : keywords) {
            if (matchesKeywordAt(text, index, keyword)) return true;
        }
        return false;
    }

    private static boolean matchesKeywordAt(String text, int index, String keyword) {
        if (index + keyword.length() > text.length()
                || !text.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        boolean beforeOk = index == 0
                || (!Character.isLetterOrDigit(text.charAt(index - 1)) && text.charAt(index - 1) != '_');
        int end = index + keyword.length();
        boolean afterOk = end == text.length()
                || (!Character.isLetterOrDigit(text.charAt(end)) && text.charAt(end) != '_');
        return beforeOk && afterOk;
    }

    /**
     * 查找SQL中顶层的FROM关键字位置
     */
    private static int findTopLevelFrom(String sql) {
        String upper = sql.toUpperCase();
        int depth = 0; // 子查询嵌套深度
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < upper.length() - 3; i++) {
            char ch = upper.charAt(i);

            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (inSingleQuote || inDoubleQuote) continue;

            if (ch == '(') { depth++; continue; }
            if (ch == ')') { depth--; continue; }

            if (depth == 0 && i + 4 <= upper.length()) {
                String word = upper.substring(i, i + 4);
                // 确保FROM是完整单词（前面不是字母，后面不是字母/数字/下划线）
                if (word.equals("FROM")) {
                    boolean prevOk = (i == 0) || !Character.isLetterOrDigit(upper.charAt(i - 1)) && upper.charAt(i - 1) != '_';
                    boolean nextOk = (i + 4 >= upper.length()) || !Character.isLetterOrDigit(upper.charAt(i + 4)) && upper.charAt(i + 4) != '_';
                    if (prevOk && nextOk) return i;
                }
            }
        }
        return -1;
    }

    /**
     * 去除SQL语句前导的注释和空白
     */
    private static String stripLeadingComments(String sql) {
        int i = 0;
        int len = sql.length();
        while (i < len) {
            // 跳过空白
            while (i < len && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
            if (i >= len) break;

            // 检查单行注释
            if (sql.charAt(i) == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') i++;
                if (i < len) i++; // 跳过换行
                continue;
            }

            // 检查多行注释
            if (sql.charAt(i) == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    if (sql.charAt(i) == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // 非注释非空白，停止
            break;
        }
        return sql.substring(i);
    }
}
