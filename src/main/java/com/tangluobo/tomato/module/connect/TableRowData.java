package com.tangluobo.tomato.module.connect;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.List;

/**
 * 表格查询结果数据
 */
public class TableRowData {
    private List<String> columnNames;
    /** 每列的 java.sql.Types 类型（如 DATE/TIME/TIMESTAMP），用于按类型渲染单元格编辑组件 */
    private List<Integer> columnTypes;
    /** JDBC 返回的底层列名；与 columnNames（显示标签）按索引对应。 */
    private List<String> sourceColumnNames = List.of();
    /** JDBC 返回的底层表名；表达式列通常为空。 */
    private List<String> sourceTableNames = List.of();
    /** JDBC 返回的底层 schema 名。 */
    private List<String> sourceSchemaNames = List.of();
    /** JDBC 返回的底层 catalog 名。 */
    private List<String> sourceCatalogNames = List.of();
    private ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
    private long totalCount;
    private int page;
    private int pageSize;
    private int totalPages;
    private long queryTime;
    private String executedSql;

    public List<String> getColumnNames() { return columnNames; }
    public void setColumnNames(List<String> columnNames) { this.columnNames = columnNames; }

    public List<Integer> getColumnTypes() { return columnTypes; }
    public void setColumnTypes(List<Integer> columnTypes) { this.columnTypes = columnTypes; }

    public List<String> getSourceColumnNames() { return sourceColumnNames; }
    public void setSourceColumnNames(List<String> sourceColumnNames) {
        this.sourceColumnNames = sourceColumnNames != null ? sourceColumnNames : List.of();
    }

    public List<String> getSourceTableNames() { return sourceTableNames; }
    public void setSourceTableNames(List<String> sourceTableNames) {
        this.sourceTableNames = sourceTableNames != null ? sourceTableNames : List.of();
    }

    public List<String> getSourceSchemaNames() { return sourceSchemaNames; }
    public void setSourceSchemaNames(List<String> sourceSchemaNames) {
        this.sourceSchemaNames = sourceSchemaNames != null ? sourceSchemaNames : List.of();
    }

    public List<String> getSourceCatalogNames() { return sourceCatalogNames; }
    public void setSourceCatalogNames(List<String> sourceCatalogNames) {
        this.sourceCatalogNames = sourceCatalogNames != null ? sourceCatalogNames : List.of();
    }

    public ObservableList<ObservableList<String>> getRows() { return rows; }
    public void setRows(ObservableList<ObservableList<String>> rows) { this.rows = rows; }

    public long getTotalCount() { return totalCount; }
    public void setTotalCount(long totalCount) { this.totalCount = totalCount; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public long getQueryTime() { return queryTime; }
    public void setQueryTime(long queryTime) { this.queryTime = queryTime; }

    public String getExecutedSql() { return executedSql; }
    public void setExecutedSql(String executedSql) { this.executedSql = executedSql; }
}
