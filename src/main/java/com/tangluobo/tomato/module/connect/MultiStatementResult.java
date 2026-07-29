package com.tangluobo.tomato.module.connect;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 多条SQL语句执行结果容器
 */
public class MultiStatementResult {
    private List<SqlStatementResult> results;
    private long totalTime;

    public List<SqlStatementResult> getResults() { return results; }
    public void setResults(List<SqlStatementResult> results) { this.results = results; }

    public long getTotalTime() { return totalTime; }
    public void setTotalTime(long totalTime) { this.totalTime = totalTime; }

    /**
     * 获取有结果集的语句结果（用于生成结果标签页）
     */
    public List<SqlStatementResult> getSelectResults() {
        return results.stream()
                .filter(r -> r.isSuccess() && r.isHasResultSet())
                .collect(Collectors.toList());
    }

    /**
     * 获取成功的语句数
     */
    public int getSuccessCount() {
        return (int) results.stream().filter(SqlStatementResult::isSuccess).count();
    }

    /**
     * 获取失败的语句数
     */
    public int getFailCount() {
        return (int) results.stream().filter(r -> !r.isSuccess()).count();
    }
}
