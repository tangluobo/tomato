package com.tangluobo.tomato.module.connect.view;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Shared context-menu actions for table data and SQL query results. */
final class TableCellContextMenuUtils {

    private static final String FILTER_SOURCE_KEY = TableCellContextMenuUtils.class.getName() + ".filterSource";
    private static final String FILTERS_KEY = TableCellContextMenuUtils.class.getName() + ".filters";

    private TableCellContextMenuUtils() {
    }

    static Menu createCopyAsMenu(TableView<ObservableList<String>> tableView,
                                 int dataColumnOffset,
                                 Supplier<String> tableNameSupplier,
                                 Supplier<List<String>> primaryKeysSupplier) {
        Menu menu = new Menu("复制为");
        MenuItem insertItem = new MenuItem("Insert 语句");
        insertItem.setOnAction(e -> copySql(tableView, dataColumnOffset,
                tableNameSupplier.get(), primaryKeysSupplier.get(), false));
        MenuItem updateItem = new MenuItem("Update 语句");
        updateItem.setOnAction(e -> copySql(tableView, dataColumnOffset,
                tableNameSupplier.get(), primaryKeysSupplier.get(), true));
        MenuItem dataItem = new MenuItem("制表符分隔值（数据）");
        dataItem.setOnAction(e -> copyText(selectedTsv(tableView, dataColumnOffset, false, true)));
        MenuItem headerItem = new MenuItem("制表符分隔值（字段名）");
        headerItem.setOnAction(e -> copyText(selectedTsv(tableView, dataColumnOffset, true, false)));
        MenuItem headerDataItem = new MenuItem("制表符分隔值（字段名和数据）");
        headerDataItem.setOnAction(e -> copyText(selectedTsv(tableView, dataColumnOffset, true, true)));
        menu.getItems().setAll(insertItem, updateItem, new javafx.scene.control.SeparatorMenuItem(),
                dataItem, headerItem, headerDataItem);
        menu.setOnShowing(e -> {
            String tableName = tableNameSupplier.get();
            List<String> primaryKeys = primaryKeysSupplier.get();
            insertItem.setDisable(tableName == null || tableName.isBlank());
            updateItem.setDisable(tableName == null || tableName.isBlank()
                    || primaryKeys == null || primaryKeys.isEmpty());
        });
        return menu;
    }

    static MenuItem createSaveDataAsItem(TableView<ObservableList<String>> tableView,
                                         int dataColumnOffset) {
        MenuItem item = new MenuItem("保存数据为...");
        item.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存数据为");
            chooser.setInitialFileName("data.tsv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("制表符分隔文件", "*.tsv"));
            File file = chooser.showSaveDialog(tableView.getScene() != null ? tableView.getScene().getWindow() : null);
            if (file == null) return;
            try {
                Files.writeString(file.toPath(), allTsv(tableView, dataColumnOffset), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "保存数据失败：" + ex.getMessage()).showAndWait();
            }
        });
        return item;
    }

    static Menu createSortMenu(TableView<ObservableList<String>> tableView, int dataColumnOffset) {
        Menu menu = new Menu("排序");
        MenuItem ascending = new MenuItem("升序排序");
        MenuItem descending = new MenuItem("降序排序");
        MenuItem sortItem = new MenuItem("排序");
        javafx.scene.shape.SVGPath arrow = new javafx.scene.shape.SVGPath();
        arrow.setContent("M3 1h2v10h3l-4 4-4-4h3z");
        arrow.setFill(javafx.scene.paint.Color.web("#42A5F5"));
        javafx.scene.shape.SVGPath lines = new javafx.scene.shape.SVGPath();
        lines.setContent("M0 1h8v1.5H0z M0 5h7v1.5H0z M0 9h6v1.5H0z M0 13h5v1.5H0z");
        lines.setFill(javafx.scene.paint.Color.web("#777777"));
        javafx.scene.layout.HBox sortIcon = new javafx.scene.layout.HBox(2, arrow, lines);
        sortIcon.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        sortItem.setGraphic(sortIcon);
        MenuItem removeItem = new MenuItem("移除排序");
        ascending.setOnAction(e -> sortFocusedColumn(tableView, dataColumnOffset, TableColumn.SortType.ASCENDING));
        descending.setOnAction(e -> sortFocusedColumn(tableView, dataColumnOffset, TableColumn.SortType.DESCENDING));
        sortItem.setOnAction(e -> addFocusedColumnSort(tableView, dataColumnOffset));
        removeItem.setOnAction(e -> removeFocusedColumnSort(tableView, dataColumnOffset));
        menu.getItems().setAll(ascending, descending, sortItem,
                new javafx.scene.control.SeparatorMenuItem(), removeItem);
        menu.setOnShowing(e -> {
            TableColumn<ObservableList<String>, ?> focusedColumn =
                    focusedDataColumn(tableView, dataColumnOffset);
            boolean unavailable = focusedColumn == null;
            ascending.setDisable(unavailable);
            descending.setDisable(unavailable);
            sortItem.setDisable(unavailable);
            removeItem.setDisable(unavailable || !tableView.getSortOrder().contains(focusedColumn));
            menu.setStyle(tableView.getSortOrder().isEmpty() ? "" : "-fx-font-weight: bold;");
        });
        return menu;
    }

    static Menu createFilterMenu(TableView<ObservableList<String>> tableView, int dataColumnOffset) {
        Menu menu = new Menu("筛选");
        MenuItem equalsItem = new MenuItem("字段 = 值");
        MenuItem notEqualsItem = new MenuItem("字段 != 值");
        MenuItem likeItem = new MenuItem("字段类似值");
        MenuItem notLikeItem = new MenuItem("字段不类似值");
        MenuItem lessItem = new MenuItem("字段 < 值");
        MenuItem greaterItem = new MenuItem("字段 > 值");
        MenuItem customItem = new MenuItem("自定义筛选");
        MenuItem filterItem = new MenuItem("筛选");
        javafx.scene.shape.SVGPath filterIcon = new javafx.scene.shape.SVGPath();
        filterIcon.setContent("M1 2h14L9.5 8v5l-3 2V8z");
        filterIcon.setFill(javafx.scene.paint.Color.web("#42A5F5"));
        filterItem.setGraphic(filterIcon);
        MenuItem removeItem = new MenuItem("移除筛选");
        MenuItem removeAllItem = new MenuItem("移除全部筛选");

        equalsItem.setOnAction(e -> filterByFocusedValue(tableView, dataColumnOffset, FilterOperator.EQUAL));
        notEqualsItem.setOnAction(e -> filterByFocusedValue(tableView, dataColumnOffset, FilterOperator.NOT_EQUAL));
        likeItem.setOnAction(e -> filterByFocusedValue(tableView, dataColumnOffset, FilterOperator.LIKE));
        notLikeItem.setOnAction(e -> filterByFocusedValue(tableView, dataColumnOffset, FilterOperator.NOT_LIKE));
        lessItem.setOnAction(e -> filterByFocusedValue(tableView, dataColumnOffset, FilterOperator.LESS));
        greaterItem.setOnAction(e -> filterByFocusedValue(tableView, dataColumnOffset, FilterOperator.GREATER));
        customItem.setOnAction(e -> showCustomFilter(tableView, dataColumnOffset));
        filterItem.setOnAction(e -> showCustomFilter(tableView, dataColumnOffset));
        removeItem.setOnAction(e -> removeFocusedColumnFilter(tableView, dataColumnOffset));
        removeAllItem.setOnAction(e -> clearAllFilters(tableView));
        menu.getItems().setAll(
                equalsItem,
                notEqualsItem,
                likeItem,
                notLikeItem,
                lessItem,
                greaterItem,
                new javafx.scene.control.SeparatorMenuItem(),
                customItem,
                filterItem,
                new javafx.scene.control.SeparatorMenuItem(),
                removeItem,
                removeAllItem
        );
        menu.setOnShowing(e -> {
            boolean unavailable = focusedDataColumn(tableView, dataColumnOffset) == null
                    || tableView.getFocusModel().getFocusedIndex() < 0;
            equalsItem.setDisable(unavailable);
            notEqualsItem.setDisable(unavailable);
            likeItem.setDisable(unavailable);
            notLikeItem.setDisable(unavailable);
            lessItem.setDisable(unavailable);
            greaterItem.setDisable(unavailable);
            customItem.setDisable(unavailable);
            filterItem.setDisable(unavailable);
            int dataColumnIndex = focusedDataColumnIndex(tableView, dataColumnOffset);
            java.util.Map<Integer, FilterCondition> filters = getFilters(tableView, false);
            removeItem.setDisable(dataColumnIndex < 0 || filters == null || !filters.containsKey(dataColumnIndex));
            removeAllItem.setDisable(filters == null || filters.isEmpty());
            menu.setStyle(filters == null || filters.isEmpty() ? "" : "-fx-font-weight: bold;");
        });
        return menu;
    }

    static Menu createDisplayMenu(TableView<ObservableList<String>> tableView, int dataColumnOffset) {
        Menu menu = new Menu("显示");
        for (int i = dataColumnOffset; i < tableView.getColumns().size(); i++) {
            TableColumn<ObservableList<String>, ?> column = tableView.getColumns().get(i);
            CheckMenuItem item = new CheckMenuItem(column.getText());
            item.setSelected(column.isVisible());
            item.setOnAction(e -> column.setVisible(item.isSelected()));
            menu.getItems().add(item);
        }
        menu.setOnShowing(e -> {
            for (int i = 0; i < menu.getItems().size(); i++) {
                ((CheckMenuItem) menu.getItems().get(i)).setSelected(
                        tableView.getColumns().get(i + dataColumnOffset).isVisible());
            }
        });
        return menu;
    }

    static void showSortPopup(TableView<ObservableList<String>> tableView,
                              int dataColumnOffset,
                              javafx.scene.Node anchor) {
        showMenuItems(createSortMenu(tableView, dataColumnOffset), anchor);
    }

    static void showFilterPopup(TableView<ObservableList<String>> tableView,
                                int dataColumnOffset,
                                javafx.scene.Node anchor) {
        showMenuItems(createFilterMenu(tableView, dataColumnOffset), anchor);
    }

    private static void showMenuItems(Menu source, javafx.scene.Node anchor) {
        List<MenuItem> items = new ArrayList<>(source.getItems());
        source.getItems().clear();
        javafx.scene.control.ContextMenu popup = new javafx.scene.control.ContextMenu();
        popup.getItems().setAll(items);
        popup.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    static void clearSortAndFilter(TableView<ObservableList<String>> tableView) {
        tableView.getSortOrder().clear();
        clearAllFilters(tableView);
    }

    private static void sortFocusedColumn(TableView<ObservableList<String>> tableView,
                                          int dataColumnOffset,
                                          TableColumn.SortType sortType) {
        TableColumn<ObservableList<String>, ?> column = focusedDataColumn(tableView, dataColumnOffset);
        if (column == null) return;
        column.setSortType(sortType);
        tableView.getSortOrder().setAll(column);
    }

    private static void addFocusedColumnSort(TableView<ObservableList<String>> tableView,
                                             int dataColumnOffset) {
        TableColumn<ObservableList<String>, ?> column = focusedDataColumn(tableView, dataColumnOffset);
        if (column == null) return;
        if (!tableView.getSortOrder().contains(column)) {
            column.setSortType(TableColumn.SortType.ASCENDING);
            tableView.getSortOrder().add(column);
        }
        tableView.sort();
    }

    private static void removeFocusedColumnSort(TableView<ObservableList<String>> tableView,
                                                int dataColumnOffset) {
        TableColumn<ObservableList<String>, ?> column = focusedDataColumn(tableView, dataColumnOffset);
        if (column != null) tableView.getSortOrder().remove(column);
    }

    private static TableColumn<ObservableList<String>, ?> focusedDataColumn(
            TableView<ObservableList<String>> tableView, int dataColumnOffset) {
        TablePosition<ObservableList<String>, ?> focused = tableView.getFocusModel().getFocusedCell();
        if (focused == null || focused.getTableColumn() == null) return null;
        int index = tableView.getColumns().indexOf(focused.getTableColumn());
        return index >= dataColumnOffset ? focused.getTableColumn() : null;
    }

    private static void filterByFocusedValue(TableView<ObservableList<String>> tableView,
                                             int dataColumnOffset,
                                             FilterOperator operator) {
        TablePosition<ObservableList<String>, ?> focused = tableView.getFocusModel().getFocusedCell();
        if (focused == null || focused.getTableColumn() == null || focused.getRow() < 0
                || focused.getRow() >= tableView.getItems().size()) return;
        int tableColumnIndex = tableView.getColumns().indexOf(focused.getTableColumn());
        int dataColumnIndex = tableColumnIndex - dataColumnOffset;
        if (dataColumnIndex < 0) return;
        ObservableList<String> focusedRow = tableView.getItems().get(focused.getRow());
        String expected = dataColumnIndex < focusedRow.size() ? focusedRow.get(dataColumnIndex) : null;
        putFilter(tableView, dataColumnIndex, new FilterCondition(operator, expected));
    }

    private static void showCustomFilter(TableView<ObservableList<String>> tableView, int dataColumnOffset) {
        int dataColumnIndex = focusedDataColumnIndex(tableView, dataColumnOffset);
        int rowIndex = tableView.getFocusModel().getFocusedIndex();
        if (dataColumnIndex < 0 || rowIndex < 0 || rowIndex >= tableView.getItems().size()) return;
        ObservableList<String> row = tableView.getItems().get(rowIndex);
        String currentValue = dataColumnIndex < row.size() ? row.get(dataColumnIndex) : null;
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(currentValue == null ? "" : currentValue);
        dialog.setTitle("自定义筛选");
        dialog.setHeaderText("输入筛选条件");
        dialog.setContentText("支持 =、!=、~、!~、<、>：");
        if (tableView.getScene() != null && tableView.getScene().getWindow() != null) {
            dialog.initOwner(tableView.getScene().getWindow());
        }
        java.util.Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;
        ParsedFilter parsed = parseFilter(result.get());
        putFilter(tableView, dataColumnIndex, new FilterCondition(parsed.operator, parsed.value));
    }

    private static ParsedFilter parseFilter(String expression) {
        String value = expression == null ? "" : expression.strip();
        if (value.startsWith("!=")) return new ParsedFilter(FilterOperator.NOT_EQUAL, value.substring(2).strip());
        if (value.startsWith("!~")) return new ParsedFilter(FilterOperator.NOT_LIKE, value.substring(2).strip());
        if (value.startsWith("=")) return new ParsedFilter(FilterOperator.EQUAL, value.substring(1).strip());
        if (value.startsWith("~")) return new ParsedFilter(FilterOperator.LIKE, value.substring(1).strip());
        if (value.startsWith("<")) return new ParsedFilter(FilterOperator.LESS, value.substring(1).strip());
        if (value.startsWith(">")) return new ParsedFilter(FilterOperator.GREATER, value.substring(1).strip());
        return new ParsedFilter(FilterOperator.LIKE, value);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Integer, FilterCondition> getFilters(
            TableView<ObservableList<String>> tableView, boolean create) {
        java.util.Map<Integer, FilterCondition> filters =
                (java.util.Map<Integer, FilterCondition>) tableView.getProperties().get(FILTERS_KEY);
        if (filters == null && create) {
            filters = new java.util.LinkedHashMap<>();
            tableView.getProperties().put(FILTERS_KEY, filters);
        }
        return filters;
    }

    private static void putFilter(TableView<ObservableList<String>> tableView,
                                  int dataColumnIndex,
                                  FilterCondition condition) {
        getFilters(tableView, true).put(dataColumnIndex, condition);
        applyFilters(tableView);
    }

    @SuppressWarnings("unchecked")
    private static void applyFilters(TableView<ObservableList<String>> tableView) {
        ObservableList<ObservableList<String>> source =
                (ObservableList<ObservableList<String>>) tableView.getProperties().get(FILTER_SOURCE_KEY);
        if (source == null) {
            source = tableView.getItems();
            tableView.getProperties().put(FILTER_SOURCE_KEY, source);
        }
        java.util.Map<Integer, FilterCondition> filters = getFilters(tableView, false);
        FilteredList<ObservableList<String>> filtered = new FilteredList<>(source, row -> {
            if (filters == null) return true;
            for (java.util.Map.Entry<Integer, FilterCondition> entry : filters.entrySet()) {
                int index = entry.getKey();
                String value = index < row.size() ? row.get(index) : null;
                if (!entry.getValue().matches(value)) return false;
            }
            return true;
        });
        if (tableView.getItems() instanceof SortedList<?> oldSorted) {
            oldSorted.comparatorProperty().unbind();
        }
        SortedList<ObservableList<String>> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sorted);
    }

    private static int focusedDataColumnIndex(TableView<ObservableList<String>> tableView,
                                              int dataColumnOffset) {
        TablePosition<ObservableList<String>, ?> focused = tableView.getFocusModel().getFocusedCell();
        if (focused == null || focused.getTableColumn() == null) return -1;
        int tableColumnIndex = tableView.getColumns().indexOf(focused.getTableColumn());
        return tableColumnIndex >= dataColumnOffset ? tableColumnIndex - dataColumnOffset : -1;
    }

    private static void removeFocusedColumnFilter(TableView<ObservableList<String>> tableView,
                                                  int dataColumnOffset) {
        int dataColumnIndex = focusedDataColumnIndex(tableView, dataColumnOffset);
        java.util.Map<Integer, FilterCondition> filters = getFilters(tableView, false);
        if (dataColumnIndex < 0 || filters == null) return;
        filters.remove(dataColumnIndex);
        if (filters.isEmpty()) clearAllFilters(tableView);
        else applyFilters(tableView);
    }

    @SuppressWarnings("unchecked")
    private static void clearAllFilters(TableView<ObservableList<String>> tableView) {
        tableView.getProperties().remove(FILTERS_KEY);
        if (tableView.getItems() instanceof SortedList<?> sorted) {
            sorted.comparatorProperty().unbind();
        }
        Object source = tableView.getProperties().remove(FILTER_SOURCE_KEY);
        if (source instanceof ObservableList<?>) {
            tableView.setItems((ObservableList<ObservableList<String>>) source);
        }
        // 切回原始列表时，VirtualFlow 可能继续复用筛选期间已变为空的单元格，
        // 导致这些单元格残留无边框样式，行选择器箭头也不再更新。
        tableView.refresh();
        tableView.requestLayout();
    }

    private static int compareValues(String left, String right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        try {
            return new java.math.BigDecimal(left.strip()).compareTo(new java.math.BigDecimal(right.strip()));
        } catch (NumberFormatException ignored) {
            return left.compareToIgnoreCase(right);
        }
    }

    private enum FilterOperator {
        EQUAL, NOT_EQUAL, LIKE, NOT_LIKE, LESS, GREATER
    }

    private static final class FilterCondition {
        private final FilterOperator operator;
        private final String expected;

        private FilterCondition(FilterOperator operator, String expected) {
            this.operator = operator;
            this.expected = expected;
        }

        private boolean matches(String actual) {
            return switch (operator) {
                case EQUAL -> Objects.equals(actual, expected);
                case NOT_EQUAL -> !Objects.equals(actual, expected);
                case LIKE -> actual != null && expected != null && actual.contains(expected);
                case NOT_LIKE -> actual == null || expected == null || !actual.contains(expected);
                case LESS -> compareValues(actual, expected) < 0;
                case GREATER -> compareValues(actual, expected) > 0;
            };
        }
    }

    private static final class ParsedFilter {
        private final FilterOperator operator;
        private final String value;

        private ParsedFilter(FilterOperator operator, String value) {
            this.operator = operator;
            this.value = value;
        }
    }

    private static String selectedTsv(TableView<ObservableList<String>> tableView,
                                      int dataColumnOffset,
                                      boolean includeHeaders,
                                      boolean includeData) {
        Set<Integer> rows = new TreeSet<>();
        Set<Integer> columns = new TreeSet<>();
        for (TablePosition<ObservableList<String>, ?> position : tableView.getSelectionModel().getSelectedCells()) {
            int column = tableView.getColumns().indexOf(position.getTableColumn());
            if (column >= dataColumnOffset) {
                rows.add(position.getRow());
                columns.add(column);
            }
        }
        return toTsv(tableView, dataColumnOffset, rows, columns, includeHeaders, includeData);
    }

    private static String allTsv(TableView<ObservableList<String>> tableView, int dataColumnOffset) {
        Set<Integer> rows = new TreeSet<>();
        for (int i = 0; i < tableView.getItems().size(); i++) rows.add(i);
        Set<Integer> columns = new TreeSet<>();
        for (int i = dataColumnOffset; i < tableView.getColumns().size(); i++) columns.add(i);
        return toTsv(tableView, dataColumnOffset, rows, columns, true, true);
    }

    private static String toTsv(TableView<ObservableList<String>> tableView,
                                int dataColumnOffset,
                                Set<Integer> rows,
                                Set<Integer> columns,
                                boolean includeHeaders,
                                boolean includeData) {
        StringBuilder result = new StringBuilder();
        if (includeHeaders) {
            appendLine(result, columns.stream().map(i -> tableView.getColumns().get(i).getText()).toList());
        }
        if (includeData) {
            for (int rowIndex : rows) {
                if (rowIndex < 0 || rowIndex >= tableView.getItems().size()) continue;
                ObservableList<String> row = tableView.getItems().get(rowIndex);
                List<String> values = new ArrayList<>();
                for (int tableColumnIndex : columns) {
                    int dataColumnIndex = tableColumnIndex - dataColumnOffset;
                    String value = dataColumnIndex < row.size() ? row.get(dataColumnIndex) : null;
                    values.add(value == null ? "NULL" : value);
                }
                appendLine(result, values);
            }
        }
        if (!result.isEmpty()) result.setLength(result.length() - System.lineSeparator().length());
        return result.toString();
    }

    private static void appendLine(StringBuilder result, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append('\t');
            result.append(values.get(i).replace("\t", " ").replace("\r", " ").replace("\n", " "));
        }
        result.append(System.lineSeparator());
    }

    private static void copySql(TableView<ObservableList<String>> tableView,
                                int dataColumnOffset,
                                String tableName,
                                List<String> primaryKeys,
                                boolean update) {
        if (tableName == null || tableName.isBlank()) return;
        Set<Integer> rows = new TreeSet<>();
        for (TablePosition<ObservableList<String>, ?> position : tableView.getSelectionModel().getSelectedCells()) {
            int column = tableView.getColumns().indexOf(position.getTableColumn());
            if (column >= dataColumnOffset) {
                rows.add(position.getRow());
            }
        }
        if (rows.isEmpty()) {
            int focusedRow = tableView.getFocusModel().getFocusedIndex();
            if (focusedRow >= 0) rows.add(focusedRow);
        }
        if (rows.isEmpty()) return;

        List<Integer> columns = new ArrayList<>();
        for (int i = dataColumnOffset; i < tableView.getColumns().size(); i++) {
            columns.add(i);
        }
        List<String> statements = new ArrayList<>();
        for (int rowIndex : rows) {
            if (rowIndex < 0 || rowIndex >= tableView.getItems().size()) continue;
            ObservableList<String> row = tableView.getItems().get(rowIndex);
            if (update) {
                List<String> assignments = new ArrayList<>();
                for (int tableColumnIndex : columns) {
                    String columnName = tableView.getColumns().get(tableColumnIndex).getText();
                    if (containsIgnoreCase(primaryKeys, columnName)) continue;
                    assignments.add(quoteIdentifier(columnName) + " = "
                            + sqlLiteral(valueAt(row, tableColumnIndex - dataColumnOffset)));
                }
                List<String> conditions = new ArrayList<>();
                for (String primaryKey : primaryKeys) {
                    int tableColumnIndex = findColumn(tableView, dataColumnOffset, primaryKey);
                    if (tableColumnIndex < 0) continue;
                    String value = valueAt(row, tableColumnIndex - dataColumnOffset);
                    conditions.add(quoteIdentifier(primaryKey) + (value == null ? " IS NULL" : " = " + sqlLiteral(value)));
                }
                if (!assignments.isEmpty() && conditions.size() == primaryKeys.size()) {
                    statements.add("UPDATE " + quoteQualifiedName(tableName) + " SET "
                            + String.join(", ", assignments) + " WHERE " + String.join(" AND ", conditions) + ";");
                }
            } else {
                List<String> names = new ArrayList<>();
                List<String> values = new ArrayList<>();
                for (int tableColumnIndex : columns) {
                    names.add(quoteIdentifier(tableView.getColumns().get(tableColumnIndex).getText()));
                    values.add(sqlLiteral(valueAt(row, tableColumnIndex - dataColumnOffset)));
                }
                statements.add("INSERT INTO " + quoteQualifiedName(tableName) + " ("
                        + String.join(", ", names) + ") VALUES (" + String.join(", ", values) + ");");
            }
        }
        copyText(String.join(System.lineSeparator(), statements));
    }

    private static String valueAt(ObservableList<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : null;
    }

    private static int findColumn(TableView<ObservableList<String>> tableView,
                                  int dataColumnOffset,
                                  String name) {
        for (int i = dataColumnOffset; i < tableView.getColumns().size(); i++) {
            if (tableView.getColumns().get(i).getText().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null) return false;
        for (String value : values) {
            if (value.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    private static String sqlLiteral(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    private static String quoteQualifiedName(String name) {
        String[] parts = name.split("\\.");
        List<String> quoted = new ArrayList<>();
        for (String part : parts) quoted.add(quoteIdentifier(part));
        return String.join(".", quoted);
    }

    private static String quoteIdentifier(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9_$]*")
                ? value
                : "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void copyText(String value) {
        if (value == null || value.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
