# 多SQL语句执行支持

## Context
当前SQL编辑器只支持执行单条SQL语句，结果区域是一个TableView。用户需要同时执行多条SQL（如多个SELECT、INSERT），并以标签页形式查看：信息摘要、各结果集、剖析、状态。

## 实现步骤

### 阶段1：基础设施（3个新文件）

**1. 新建 `SqlSplitter.java`** — SQL拆分工具类
- 有限状态机逐字符扫描，正确处理字符串(`'`)、注释(`--`, `/* */`)中的分号
- 状态：NORMAL / IN_SINGLE_QUOTE / IN_LINE_COMMENT / IN_BLOCK_COMMENT
- 遇到 `;` 且在 NORMAL 状态时，截取为一条语句（去除首尾空白后非空则收集）
- 返回 `List<String>`

**2. 新建 `SqlStatementResult.java`** — 单条语句执行结果
- sql, success, errorMessage, queryTime, hasResultSet, resultData(TableRowData), updateCount, isSelect

**3. 新建 `MultiStatementResult.java`** — 多语句结果容器
- results(List<SqlStatementResult>), totalTime
- 便捷方法：getSelectResults(), getSuccessCount(), getFailCount()

### 阶段2：DatabaseService 扩展

**4. 新增辅助方法 `isSelectStatement(String sql)`**
- 去除前导注释和空白后，判断是否以 SELECT/WITH/SHOW/DESCRIBE/EXPLAIN 开头

**5. 新增 `executeMultiSqlQuery(config, dbName, sql, pageSize)`**
- 调用 SqlSplitter.split() 拆分语句
- 复用同一 Connection，每条语句独立 Statement
- 一条失败不中断后续，异常记录到 SqlStatementResult.errorMessage
- 汇总为 MultiStatementResult 返回

**6. 新增 `executeExplainQuery(config, dbName, sql)`**
- 对单条 SELECT 执行 EXPLAIN，返回 TableRowData

**7. 新增 `executeStatusQuery(config, dbName)`**
- MySQL: `SHOW STATUS`，PostgreSQL: `SELECT name, setting FROM pg_settings`

### 阶段3：SqlEditorView UI重构

**8. 结果区域改为 TabPane**
- 移除 resultTable + statusLabel
- 新增 `TabPane resultTabPane`，设 TabClosingPolicy.UNAVAILABLE

**9. 修改 `getEffectiveSql()`**
- 不再去除末尾分号，由 SqlSplitter 统一处理

**10. 重构 `executeQuery()`**
- 拆分SQL → 清空标签页 → 后台执行 executeMultiSqlQuery + explain + status → 构建标签页

**11. 标签页生成方法**
- `buildInfoTab(MultiStatementResult)` — TextArea，格式化每条SQL的状态：
  ```
  select * from account 
   > OK 
   > 时间: 0.087s 
  ```
- `buildResultTab(index, SqlStatementResult)` — TableView，复用现有填充逻辑
- `buildExplainTab(List<TableRowData>)` — TableView 展示 EXPLAIN 结果
- `buildStatusTab(TableRowData)` — TableView 展示服务器状态

**12. 标签顺序**：信息 → 结果1/结果2/... → 剖析 → 状态

**13. 默认选中**：有错误选信息，否则选第一个结果（无结果时选信息）

## 关键文件
- `SqlEditorView.java` — UI重构主战场
- `DatabaseService.java` — 新增3个执行方法
- `SqlSplitter.java` — 新建
- `SqlStatementResult.java` — 新建
- `MultiStatementResult.java` — 新建

## 验证
- 输入多条SELECT，验证信息标签和多个结果标签
- 输入混合SELECT+INSERT+DDL，验证各语句独立执行
- 输入错误SQL，验证错误信息显示且不中断后续语句
- 点击剖析/状态标签，验证内容正确
