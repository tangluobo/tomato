package com.tangluobo.tomato.module.connect;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.List;

/**
 * 表格查询结果数据
 */
public class TableRowData {
    private List<String> columnNames;
    private ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
    private long totalCount;
    private int page;
    private int pageSize;
    private int totalPages;
    private long queryTime;

    public List<String> getColumnNames() { return columnNames; }
    public void setColumnNames(List<String> columnNames) { this.columnNames = columnNames; }

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
}
