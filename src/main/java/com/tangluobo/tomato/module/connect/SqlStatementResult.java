package com.tangluobo.tomato.module.connect;

/**
 * 单条SQL语句执行结果
 */
public class SqlStatementResult {
    private String sql;
    private boolean success;
    private String errorMessage;
    private long queryTime;
    private boolean hasResultSet;
    private TableRowData resultData;
    private int updateCount;
    private boolean isSelect;

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getQueryTime() { return queryTime; }
    public void setQueryTime(long queryTime) { this.queryTime = queryTime; }

    public boolean isHasResultSet() { return hasResultSet; }
    public void setHasResultSet(boolean hasResultSet) { this.hasResultSet = hasResultSet; }

    public TableRowData getResultData() { return resultData; }
    public void setResultData(TableRowData resultData) { this.resultData = resultData; }

    public int getUpdateCount() { return updateCount; }
    public void setUpdateCount(int updateCount) { this.updateCount = updateCount; }

    public boolean isSelect() { return isSelect; }
    public void setSelect(boolean select) { isSelect = select; }
}
