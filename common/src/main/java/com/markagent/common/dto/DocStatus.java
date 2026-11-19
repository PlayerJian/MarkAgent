package com.markagent.common.dto;

/** 文档解析状态：上传 → 解析 →（索引，M2）→ 可检索 */
public enum DocStatus {
    /** 解析中（含上传受理、MinerU 解析、产物落盘） */
    PARSING,
    /** 解析完成，md + content_list 已冻结落盘 */
    READY,
    /** 解析失败（error 携带原因，可 retry 重跑） */
    FAILED
}
