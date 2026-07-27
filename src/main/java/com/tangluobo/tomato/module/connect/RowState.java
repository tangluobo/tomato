package com.tangluobo.tomato.module.connect;

/**
 * 表格行状态枚举：用于追踪行的编辑状态
 */
public enum RowState {
    /** 从数据库加载的原始行，未修改 */
    EXISTING,
    /** 从数据库加载的行，有本地修改尚未保存 */
    EXISTING_DIRTY,
    /** 尚未持久化到数据库的新行 */
    NEW
}
