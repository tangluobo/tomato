package com.tangluobo.tomato.module.connect;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Infers a sensible initial column type from a field name.
 *
 * <p>The inference is deliberately used only for columns whose type is still
 * empty. It is a convenience default, not a schema rule, so a type selected by
 * the user must never be replaced by this class.</p>
 */
public final class FieldTypeInference {

    private static final Set<String> BOOLEAN_WORDS = Set.of(
            "active", "available", "deleted", "disabled", "enabled", "flag",
            "locked", "required", "valid", "visible");
    private static final Set<String> INTEGER_WORDS = Set.of(
            "age", "count", "index", "level", "number", "num", "order",
            "position", "quantity", "qty", "rank", "sequence", "seq", "sort", "version");
    private static final Set<String> DECIMAL_WORDS = Set.of(
            "amount", "balance", "cost", "discount", "height", "percent",
            "percentage", "price", "rate", "ratio", "salary", "score", "weight");
    private static final Set<String> LONG_TEXT_WORDS = Set.of(
            "body", "content", "description", "detail", "details", "memo",
            "remark", "remarks", "text");
    private static final Set<String> BINARY_WORDS = Set.of("binary", "blob", "bytes");

    private FieldTypeInference() {
    }

    /**
     * @param connectType database dialect
     * @param fieldName field name entered by the user
     * @param availableTypes types supported by the connected database version;
     *                       used for version-dependent JSON support
     * @return a database-specific suggestion, or {@code null} for an empty
     *         field name or a non-relational connection type
     */
    public static Suggestion infer(ConnectType connectType, String fieldName,
                                   List<String> availableTypes) {
        if (connectType == null || fieldName == null || fieldName.isBlank()) return null;

        NameParts name = NameParts.of(fieldName);
        Kind kind = classify(name);
        return switch (connectType) {
            case MYSQL -> mysqlSuggestion(kind, availableTypes);
            case POSTGRESQL -> postgresqlSuggestion(kind);
            case ORACLE -> oracleSuggestion(kind, availableTypes);
            case HIVE -> hiveSuggestion(kind);
            default -> null;
        };
    }

    public static Suggestion infer(ConnectType connectType, String fieldName) {
        return infer(connectType, fieldName, Collections.emptyList());
    }

    private static Kind classify(NameParts name) {
        // UUID/GUID must be checked before generic identifier matching.
        if (name.hasAny("uuid", "guid") || name.originalContains("唯一标识")) return Kind.UUID;
        if (name.has("id") || name.originalMatchesAny("编号", "主键")) return Kind.ID;

        boolean hasDate = name.hasAny("date", "day", "birthday", "birthdate")
                || name.originalContains("日期") || name.originalContains("生日");
        boolean hasTime = name.has("time") || name.originalContains("时间");
        boolean explicitDateTime = name.hasAny("datetime", "datatime", "timestamp")
                || name.endsWithAt()
                || name.originalContains("日期时间")
                || name.originalContains("时间戳")
                || name.originalMatchesAny("创建时间", "更新时间", "修改时间", "删除时间");
        if (explicitDateTime || (hasDate && hasTime)) return Kind.DATETIME;
        if (hasDate) return Kind.DATE;
        if (hasTime) return Kind.TIME;

        if (name.hasAny("json", "jsonb")) return Kind.JSON;
        if (name.hasAny(BINARY_WORDS)) return Kind.BINARY;
        if (name.hasAny(LONG_TEXT_WORDS)) return Kind.LONG_TEXT;
        if (name.startsWithAny("is", "has", "can") || name.hasAny(BOOLEAN_WORDS)
                || name.originalContains("是否")) return Kind.BOOLEAN;
        if (name.hasAny(DECIMAL_WORDS)) return Kind.DECIMAL;
        if (name.hasAny(INTEGER_WORDS)) return Kind.INTEGER;
        return Kind.STRING;
    }

    private static Suggestion mysqlSuggestion(Kind kind, List<String> availableTypes) {
        return switch (kind) {
            case ID -> new Suggestion("BIGINT", "", "");
            case DATE -> new Suggestion("DATE", "", "");
            case TIME -> new Suggestion("TIME", "", "");
            case DATETIME -> new Suggestion("DATETIME", "", "");
            case UUID -> new Suggestion("CHAR", "36", "");
            case BOOLEAN -> new Suggestion("BOOLEAN", "", "");
            case INTEGER -> new Suggestion("INT", "", "");
            case DECIMAL -> new Suggestion("DECIMAL", "18", "2");
            case LONG_TEXT -> new Suggestion("TEXT", "", "");
            case BINARY -> new Suggestion("BLOB", "", "");
            case JSON -> containsIgnoreCase(availableTypes, "JSON")
                    ? new Suggestion("JSON", "", "")
                    : new Suggestion("LONGTEXT", "", "");
            case STRING -> new Suggestion("VARCHAR", "255", "");
        };
    }

    private static Suggestion postgresqlSuggestion(Kind kind) {
        return switch (kind) {
            case ID -> new Suggestion("bigint", "", "");
            case DATE -> new Suggestion("date", "", "");
            case TIME -> new Suggestion("time", "", "");
            case DATETIME -> new Suggestion("timestamp", "", "");
            case UUID -> new Suggestion("uuid", "", "");
            case BOOLEAN -> new Suggestion("boolean", "", "");
            case INTEGER -> new Suggestion("integer", "", "");
            case DECIMAL -> new Suggestion("numeric", "18", "2");
            case LONG_TEXT -> new Suggestion("text", "", "");
            case BINARY -> new Suggestion("bytea", "", "");
            case JSON -> new Suggestion("jsonb", "", "");
            case STRING -> new Suggestion("character varying", "255", "");
        };
    }

    private static Suggestion oracleSuggestion(Kind kind, List<String> availableTypes) {
        return switch (kind) {
            case ID -> new Suggestion("NUMBER", "19", "");
            case DATE -> new Suggestion("DATE", "", "");
            // Oracle has no standalone SQL TIME type. TIMESTAMP is the closest
            // native temporal type and avoids storing a clock value as text.
            case TIME, DATETIME -> new Suggestion("TIMESTAMP", "", "");
            case UUID -> new Suggestion("VARCHAR2", "36", "");
            case BOOLEAN -> new Suggestion("NUMBER", "1", "");
            case INTEGER -> new Suggestion("NUMBER", "10", "");
            // Unconstrained NUMBER safely supports fractional values on all
            // supported Oracle versions without assuming a business precision.
            case DECIMAL -> new Suggestion("NUMBER", "", "");
            case LONG_TEXT -> new Suggestion("CLOB", "", "");
            case BINARY -> new Suggestion("BLOB", "", "");
            case JSON -> containsIgnoreCase(availableTypes, "JSON")
                    ? new Suggestion("JSON", "", "")
                    : new Suggestion("CLOB", "", "");
            case STRING -> new Suggestion("VARCHAR2", "255", "");
        };
    }

    private static Suggestion hiveSuggestion(Kind kind) {
        return switch (kind) {
            case ID -> new Suggestion("BIGINT", "", "");
            case DATE -> new Suggestion("DATE", "", "");
            case TIME, DATETIME -> new Suggestion("TIMESTAMP", "", "");
            case UUID -> new Suggestion("STRING", "", "");
            case BOOLEAN -> new Suggestion("BOOLEAN", "", "");
            case INTEGER -> new Suggestion("INT", "", "");
            case DECIMAL -> new Suggestion("DECIMAL", "18", "2");
            case LONG_TEXT, JSON, STRING -> new Suggestion("STRING", "", "");
            case BINARY -> new Suggestion("BINARY", "", "");
        };
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null) return false;
        return values.stream().anyMatch(value -> expected.equalsIgnoreCase(value));
    }

    private enum Kind {
        ID, DATE, TIME, DATETIME, UUID, BOOLEAN, INTEGER, DECIMAL,
        LONG_TEXT, BINARY, JSON, STRING
    }

    public record Suggestion(String type, String length, String decimalPlaces) {
    }

    private record NameParts(String original, List<String> tokens) {

        static NameParts of(String fieldName) {
            String separatedCamelCase = fieldName.trim()
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                    .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
            String normalized = separatedCamelCase.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9\\p{IsHan}]+", "_");
            List<String> tokens = Arrays.stream(normalized.split("_+"))
                    .filter(token -> !token.isBlank())
                    .toList();
            return new NameParts(fieldName.trim().toLowerCase(Locale.ROOT), tokens);
        }

        boolean has(String token) {
            return tokens.contains(token);
        }

        boolean hasAny(String... candidates) {
            return hasAny(new HashSet<>(Arrays.asList(candidates)));
        }

        boolean hasAny(Set<String> candidates) {
            return tokens.stream().anyMatch(candidates::contains);
        }

        boolean startsWithAny(String... candidates) {
            return !tokens.isEmpty() && Arrays.asList(candidates).contains(tokens.getFirst());
        }

        boolean endsWithAt() {
            return tokens.size() > 1 && "at".equals(tokens.getLast());
        }

        boolean originalContains(String text) {
            return original.contains(text);
        }

        boolean originalMatchesAny(String... fragments) {
            return Arrays.stream(fragments).anyMatch(original::contains);
        }
    }
}
