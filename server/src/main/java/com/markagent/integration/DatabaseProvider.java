package com.markagent.integration;

/**
 * 数据库查询能力接口（可插拔企业集成层）。
 * <p>
 * 默认实现 {@link DefaultDatabaseProvider}：JDK 内置 DriverManager 直连，
 * 运行时开关 markagent.integration.database.enabled（默认 false）控制启用。
 * 接入真实数据平台（数仓网关、只读副本等）时提供本接口的替代实现并替换 Bean 即可。
 * <p>
 * 返回约定：成功时首行为摘要「共 N 行」（截断时附加说明），第二行为列名行
 * （列以「 | 」分隔），其后每行一条数据；失败时返回以「错误：」开头的文本。
 * 实现不得抛出异常，错误一律以文本返回。
 */
public interface DatabaseProvider {

    /**
     * 执行只读 SQL 查询并返回结构化文本。
     *
     * @param sql 只读 SQL（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH 开头，单语句）
     * @return 结构化结果文本；失败以「错误：」开头
     */
    String query(String sql);
}
