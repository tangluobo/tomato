package com.tangluobo.tomato.module.connect.view;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL 编辑器自动补全的纯逻辑部分。
 *
 * <p>除了普通关键字/元数据补全外，还能根据光标前的限定名选择下一层候选：
 * {@code database.} 提示表，{@code table.} 和 {@code database.table.} 提示字段。</p>
 */
public final class SqlCompletionEngine {

    private SqlCompletionEngine() {
    }

    public record Candidate(String text, String kind) {
    }

    public record Completion(int replacementStart, List<Candidate> candidates) {
        private static Completion empty(int replacementStart) {
            return new Completion(replacementStart, List.of());
        }
    }

    /**
     * 计算光标位置对应的候选项。
     *
     * @param text SQL 文本
     * @param caret 光标偏移
     * @param unqualifiedItems 未使用点号时的全部候选
     * @param currentDatabase 当前编辑器选中的数据库
     * @param activeNamespaces 当前元数据所属的限定名（数据库、schema/owner）
     * @param columnsByTable 当前库中每张表对应的字段
     */
    public static Completion complete(String text,
                                      int caret,
                                      Collection<Candidate> unqualifiedItems,
                                      String currentDatabase,
                                      Collection<String> activeNamespaces,
                                      Map<String, ? extends Collection<String>> columnsByTable) {
        ParsedReference reference = parseReference(text, caret);
        if (reference == null) return Completion.empty(Math.max(0, caret));

        Collection<Candidate> source;
        if (reference.qualifiers().isEmpty()) {
            if (reference.prefix().isEmpty()) return Completion.empty(reference.replacementStart());
            source = unqualifiedItems != null ? unqualifiedItems : List.of();
        } else if (reference.qualifiers().size() == 1) {
            String qualifier = reference.qualifiers().get(0);
            Collection<String> columns = findTableColumns(columnsByTable, qualifier);
            if (columns != null) {
                source = candidates(columns, "字段");
            } else if (isActiveNamespace(qualifier, currentDatabase, activeNamespaces)) {
                source = tableCandidates(columnsByTable);
            } else {
                return Completion.empty(reference.replacementStart());
            }
        } else {
            String table = reference.qualifiers().get(reference.qualifiers().size() - 1);
            if (!containsActiveNamespace(reference.qualifiers().subList(
                    0, reference.qualifiers().size() - 1), currentDatabase, activeNamespaces)) {
                return Completion.empty(reference.replacementStart());
            }
            Collection<String> columns = findTableColumns(columnsByTable, table);
            if (columns == null) return Completion.empty(reference.replacementStart());
            source = candidates(columns, "字段");
        }

        String lowerPrefix = normalizeIdentifier(reference.prefix()).toLowerCase(Locale.ROOT);
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate item : source) {
            if (item == null || item.text() == null || item.text().isBlank()) continue;
            String key = item.text().toLowerCase(Locale.ROOT);
            if (!key.startsWith(lowerPrefix)) continue;
            if (!lowerPrefix.isEmpty() && key.equals(lowerPrefix)) continue;
            unique.putIfAbsent(key, item);
        }
        List<Candidate> matches = unique.values().stream()
                .sorted(Comparator.comparingInt((Candidate item) -> kindOrder(item.kind()))
                        .thenComparing(Candidate::text, String.CASE_INSENSITIVE_ORDER))
                .limit(100)
                .toList();
        return new Completion(reference.replacementStart(), matches);
    }

    private static Collection<Candidate> tableCandidates(
            Map<String, ? extends Collection<String>> columnsByTable) {
        if (columnsByTable == null || columnsByTable.isEmpty()) return List.of();
        return candidates(columnsByTable.keySet(), "表");
    }

    private static Collection<Candidate> candidates(Collection<String> names, String kind) {
        if (names == null || names.isEmpty()) return List.of();
        List<Candidate> result = new ArrayList<>(names.size());
        for (String name : names) {
            if (name != null && !name.isBlank()) result.add(new Candidate(name, kind));
        }
        return result;
    }

    private static Collection<String> findTableColumns(
            Map<String, ? extends Collection<String>> columnsByTable, String table) {
        if (columnsByTable == null || table == null) return null;
        String normalizedTable = normalizeIdentifier(table);
        for (Map.Entry<String, ? extends Collection<String>> entry : columnsByTable.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(normalizedTable)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean containsActiveNamespace(Collection<String> qualifiers,
                                                   String currentDatabase,
                                                   Collection<String> activeNamespaces) {
        for (String qualifier : qualifiers) {
            if (isActiveNamespace(qualifier, currentDatabase, activeNamespaces)) return true;
        }
        return false;
    }

    private static boolean isActiveNamespace(String qualifier,
                                             String currentDatabase,
                                             Collection<String> activeNamespaces) {
        String normalized = normalizeIdentifier(qualifier);
        if (currentDatabase != null && currentDatabase.equalsIgnoreCase(normalized)) return true;
        if (activeNamespaces != null) {
            for (String namespace : activeNamespaces) {
                if (namespace != null && namespace.equalsIgnoreCase(normalized)) return true;
            }
        }
        return false;
    }

    private record ParsedReference(int replacementStart, String prefix, List<String> qualifiers) {
    }

    private static ParsedReference parseReference(String text, int caret) {
        if (text == null || caret <= 0 || caret > text.length()) return null;
        int start = caret;
        while (start > 0 && isQualifiedIdentifierPart(text.charAt(start - 1))) start--;
        String token = text.substring(start, caret);
        if (token.isEmpty()) return null;

        String[] segments = token.split("\\.", -1);
        if (segments.length == 0) return null;
        List<String> qualifiers = new ArrayList<>(Math.max(0, segments.length - 1));
        for (int i = 0; i < segments.length - 1; i++) {
            String qualifier = normalizeIdentifier(segments[i]);
            if (qualifier.isEmpty()) return null;
            qualifiers.add(qualifier);
        }
        String prefix = segments[segments.length - 1];
        int lastDot = token.lastIndexOf('.');
        int replacementStart = lastDot >= 0 ? start + lastDot + 1 : start;
        return new ParsedReference(replacementStart, prefix, List.copyOf(qualifiers));
    }

    private static boolean isQualifiedIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.'
                || c == '`' || c == '"' || c == '[' || c == ']';
    }

    private static String normalizeIdentifier(String identifier) {
        if (identifier == null) return "";
        String value = identifier.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                return value.substring(1, value.length() - 1);
            }
        }
        if (!value.isEmpty() && (value.charAt(0) == '`' || value.charAt(0) == '"'
                || value.charAt(0) == '[')) {
            return value.substring(1);
        }
        return value;
    }

    private static int kindOrder(String kind) {
        return switch (kind) {
            case "关键字" -> 0;
            case "数据库" -> 1;
            case "表" -> 2;
            case "字段" -> 3;
            default -> 4;
        };
    }
}
