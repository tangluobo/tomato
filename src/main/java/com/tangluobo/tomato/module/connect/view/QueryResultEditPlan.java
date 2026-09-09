package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 查询结果到真实表列的安全回写计划。 */
record QueryResultEditPlan(List<String> databaseColumnNames,
                           Set<Integer> editableColumnIndexes,
                           List<String> primaryKeyColumns,
                           boolean usesOriginalRowMatch,
                           String statusText) {

    QueryResultEditPlan {
        databaseColumnNames = List.copyOf(databaseColumnNames);
        editableColumnIndexes = Set.copyOf(editableColumnIndexes);
        primaryKeyColumns = List.copyOf(primaryKeyColumns);
    }

    static QueryResultEditPlan create(ConnectType connectionType,
                                      String targetTableName,
                                      List<String> resultColumnNames,
                                      List<String> sourceColumnNames,
                                      List<String> sourceTableNames,
                                      List<Map<String, String>> tableColumns) {
        List<String> resultColumns = resultColumnNames != null ? resultColumnNames : List.of();
        List<String> actualColumns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        if (tableColumns != null) {
            for (Map<String, String> column : tableColumns) {
                String name = column != null ? column.get("字段名") : null;
                if (name == null || name.isBlank()) continue;
                actualColumns.add(name);
                if ("是".equals(column.get("主键"))) primaryKeys.add(name);
            }
        }

        List<String> databaseColumns = new ArrayList<>(resultColumns.size());
        // 未映射列必须使用不可能与真实数据库列重名的内部占位符，避免表达式别名
        // （例如 42 AS id）在主键索引查找时抢先命中。
        for (int index = 0; index < resultColumns.size(); index++) {
            databaseColumns.add("\u0000query-result-column-" + index);
        }
        List<String> selectedSourceColumns = new ArrayList<>();
        Set<Integer> sourceColumnIndexes = new LinkedHashSet<>();
        Set<String> mappedSourceColumns = new LinkedHashSet<>();
        // DatabaseService 提供的来源列表与结果列等长时，空表名明确表示表达式，不能再按别名回退。
        boolean hasReliableTableOrigins = sourceTableNames != null
                && sourceTableNames.size() == resultColumns.size();

        for (int index = 0; index < resultColumns.size(); index++) {
            String sourceTable = valueAt(sourceTableNames, index);
            if (hasReliableTableOrigins
                    && (sourceTable.isBlank() || !sourceTable.equalsIgnoreCase(targetTableName))) {
                continue;
            }
            String sourceColumn = valueAt(sourceColumnNames, index);
            if (hasReliableTableOrigins && sourceColumn.isBlank()) continue;
            String label = resultColumns.get(index);
            String candidate = sourceColumn.isBlank() ? label : sourceColumn;
            String actualColumn = resolveActualColumn(actualColumns, candidate);
            if (actualColumn == null) continue;
            if (!mappedSourceColumns.add(actualColumn)) continue;
            databaseColumns.set(index, actualColumn);
            selectedSourceColumns.add(actualColumn);
            sourceColumnIndexes.add(index);
        }

        boolean hasCompletePrimaryKey = !primaryKeys.isEmpty()
                && containsAllColumns(selectedSourceColumns, primaryKeys);
        boolean canMatchOriginalRow = connectionType == ConnectType.MYSQL
                && hasReliableTableOrigins
                && sourceColumnIndexes.size() == resultColumns.size()
                && containsAllColumns(selectedSourceColumns, actualColumns);

        Set<Integer> editableIndexes = new LinkedHashSet<>();
        if (hasCompletePrimaryKey || canMatchOriginalRow) {
            editableIndexes.addAll(sourceColumnIndexes);
        }

        String status;
        if (!editableIndexes.isEmpty()) {
            status = canMatchOriginalRow && !hasCompletePrimaryKey
                    ? "可编辑 · 无完整主键，保存时按原始整行定位"
                    : "可编辑 · 双击单元格编辑，Ctrl+V 粘贴修改";
        } else if (actualColumns.isEmpty()) {
            status = "只读结果 · 未找到源表或源表没有可编辑列";
        } else if (primaryKeys.isEmpty()) {
            status = connectionType == ConnectType.MYSQL
                    ? "只读结果 · 无主键表需查询全部真实列"
                    : "只读结果 · 表没有主键";
        } else {
            status = connectionType == ConnectType.MYSQL
                    ? "只读结果 · 查询结果需包含完整主键或全部真实列"
                    : "只读结果 · 查询结果需包含完整主键";
        }

        return new QueryResultEditPlan(databaseColumns, editableIndexes,
                hasCompletePrimaryKey ? primaryKeys : List.of(),
                canMatchOriginalRow && !hasCompletePrimaryKey, status);
    }

    boolean isEditable() {
        return !editableColumnIndexes.isEmpty();
    }

    boolean canDelete() {
        return isEditable() && !primaryKeyColumns.isEmpty();
    }

    private static boolean containsAllColumns(Collection<String> available,
                                              Collection<String> required) {
        for (String column : required) {
            if (!available.contains(column)) return false;
        }
        return true;
    }

    /** 精确大小写优先；仅当忽略大小写后唯一时才兼容驱动的大小写差异。 */
    private static String resolveActualColumn(List<String> actualColumns, String candidate) {
        if (candidate == null) return null;
        for (String actual : actualColumns) {
            if (actual.equals(candidate)) return actual;
        }
        String match = null;
        for (String actual : actualColumns) {
            if (!actual.equalsIgnoreCase(candidate)) continue;
            if (match != null && !match.equals(actual)) return null;
            match = actual;
        }
        return match;
    }

    private static String valueAt(List<String> values, int index) {
        if (values == null || index < 0 || index >= values.size()) return "";
        String value = values.get(index);
        return value != null ? value : "";
    }

}
