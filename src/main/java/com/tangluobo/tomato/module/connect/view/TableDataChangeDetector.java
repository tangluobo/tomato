package com.tangluobo.tomato.module.connect.view;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 打开表和查询结果共用的纯数据变更检测。 */
final class TableDataChangeDetector {

    private TableDataChangeDetector() {
    }

    static boolean hasPersistableValue(List<String> row) {
        if (row == null) return false;
        for (String value : row) {
            if (value != null && !value.isEmpty()) return true;
        }
        return false;
    }

    static boolean hasChanges(List<String> current, List<String> original) {
        int currentSize = current == null ? 0 : current.size();
        int originalSize = original == null ? 0 : original.size();
        int columnCount = Math.max(currentSize, originalSize);
        for (int index = 0; index < columnCount; index++) {
            if (!Objects.equals(valueAt(current, index), valueAt(original, index))) return true;
        }
        return false;
    }

    static boolean hasChanges(List<String> current, List<String> original,
                              Collection<Integer> columnIndexes) {
        if (columnIndexes == null || columnIndexes.isEmpty()) return false;
        for (Integer index : columnIndexes) {
            if (index != null && index >= 0
                    && !Objects.equals(valueAt(current, index), valueAt(original, index))) {
                return true;
            }
        }
        return false;
    }

    static Set<Integer> changedColumnIndexes(List<String> current, List<String> original) {
        int currentSize = current == null ? 0 : current.size();
        int originalSize = original == null ? 0 : original.size();
        Set<Integer> result = new LinkedHashSet<>();
        for (int index = 0; index < Math.max(currentSize, originalSize); index++) {
            if (!Objects.equals(valueAt(current, index), valueAt(original, index))) {
                result.add(index);
            }
        }
        return result;
    }

    private static String valueAt(List<String> values, int index) {
        return values != null && index < values.size() ? values.get(index) : "";
    }
}
