package com.markagent.docs;

/**
 * 文档块（检索与索引的原子单元）：来自 MinerU 产物 structured_content.json（4.x）
 * 或 content_list（3.x 兼容）。
 *
 * @param index 块序号（文档内唯一，0 起，即块序列下标）
 * @param type  块类型（paragraph_title/text/table/image/formula...）
 * @param text  块文本（已扁平化）
 * @param page  页码（1 起；无页码信息时为 0）
 */
public record DocBlock(int index, String type, String text, int page) {
}
