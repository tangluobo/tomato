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
