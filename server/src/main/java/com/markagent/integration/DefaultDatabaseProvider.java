package com.markagent.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据库查询默认实现：java.sql.DriverManager（JDK 内置，不新增 maven 依赖）直连。
 * <p>
 * 运行时开关（改配置重启即生效，v1 不用条件装配）：
 * <ul>
 *   <li>markagent.integration.database.enabled（默认 false）</li>
 *   <li>markagent.integration.database.jdbc-url（默认空）</li>
 *   <li>markagent.integration.database.username（默认空）</li>
 *   <li>markagent.integration.database.password（默认空，yml 用 ${DB_PASSWORD:} 注入）</li>
 * </ul>
 * enabled=false 或 jdbc-url 为空 → 返回明确错误文本（不抛异常）。
 * <p>
 * 安全护栏见 {@link SqlGuard}：仅只读单语句、结果上限 {@value #MAX_ROWS} 行、
 * 单格文本截断 {@value #MAX_CELL_CHARS} 字符、查询超时 {@value #QUERY_TIMEOUT_SECONDS} 秒。
 * <p>
 * 驱动按 jdbc-url 前缀推导并 Class.forName 显式加载（mysql/postgresql/mariadb/h2/sqlite/
 * sqlserver/oracle）；classpath 缺驱动时返回明确错误「缺少 JDBC 驱动（classpath 无 xxx）」。
 * 未知前缀不显式加载驱动（依赖驱动自注册），连接失败时错误文本提示需添加对应驱动依赖。
 */
@Component
public class DefaultDatabaseProvider implements DatabaseProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultDatabaseProvider.class);

    /** 结果行数上限 */
    static final int MAX_ROWS = 100;
    /** 单格文本截断长度 */
    static final int MAX_CELL_CHARS = 500;
    /** 查询超时（秒） */
    static final int QUERY_TIMEOUT_SECONDS = 30;

    /** 常见 JDBC URL 前缀 → 驱动类名（显式加载；未知前缀依赖 SPI 自动注册） */
    private static final Map<String, String> KNOWN_DRIVERS = Map.of(
            "jdbc:mysql:", "com.mysql.cj.jdbc.Driver",
            "jdbc:postgresql:", "org.postgresql.Driver",
            "jdbc:mariadb:", "org.mariadb.jdbc.Driver",
            "jdbc:h2:", "org.h2.Driver",
            "jdbc:sqlite:", "org.sqlite.JDBC",
            "jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:oracle:", "oracle.jdbc.OracleDriver");

    private final boolean enabled;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DefaultDatabaseProvider(
            @Value("${markagent.integration.database.enabled:false}") boolean enabled,
            @Value("${markagent.integration.database.jdbc-url:}") String jdbcUrl,
            @Value("${markagent.integration.database.username:}") String username,
            @Value("${markagent.integration.database.password:}") String password) {
        this.enabled = enabled;
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        this.username = username;
        this.password = password;
    }

    @Override
    public String query(String sql) {
        if (!enabled || jdbcUrl.isEmpty()) {
            return "错误：数据库集成未启用（markagent.integration.database.enabled=false），请配置后重试";
        }
        String violation = SqlGuard.check(sql);
        if (violation != null) {
            return violation;
        }
        try {
            return execute(sql);
        } catch (Exception e) {
            log.warn("数据库查询失败: {}", e.getMessage());
            return "错误：查询失败 - " + e.getMessage();
        }
    }

    /** 驱动加载 + 连接执行 + 结构化格式化 */
    private String execute(String sql) {
        String driver = driverFor(jdbcUrl);
        if (driver != null) {
            try {
                Class.forName(driver);
            } catch (ClassNotFoundException e) {
                return "错误：缺少 JDBC 驱动（classpath 无 " + driver + "），需添加对应驱动依赖";
            }
        }
        String user = username == null || username.isBlank() ? null : username;
        String pass = password == null || password.isBlank() ? null : password;
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                return format(rs);
            }
        } catch (SQLException e) {
            String prefix = driver == null
                    ? "错误：查询失败（未识别的 JDBC URL 前缀，需 classpath 含对应驱动依赖）- "
                    : "错误：查询失败 - ";
            return prefix + e.getMessage();
        }
    }

    /** 按 jdbc-url 前缀推导驱动类名（未知前缀返回 null，依赖驱动自注册） */
    static String driverFor(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : KNOWN_DRIVERS.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 结果格式化：首行摘要「共 N 行」（达上限时附加截断说明），第二行列名行，
     * 其后每行一条数据（列以「 | 」分隔）。单元格内换行替换为空格、超长截断。
     */
    private String format(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        List<String> headers = new ArrayList<>();
        for (int i = 1; i <= cols; i++) {
            headers.add(meta.getColumnLabel(i));
        }
        StringBuilder rows = new StringBuilder();
        int count = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (count >= MAX_ROWS) {
                truncated = true;
                break;
            }
            List<String> cells = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                cells.add(cell(rs.getString(i)));
            }
            rows.append(String.join(" | ", cells)).append('\n');
            count++;
        }
        String summary = "共 " + count + " 行";
        if (truncated) {
            summary += "（已达 " + MAX_ROWS + " 行上限，后续行未显示）";
        }
        StringBuilder out = new StringBuilder(summary).append('\n');
        if (!headers.isEmpty()) {
            out.append(String.join(" | ", headers)).append('\n');
        }
        out.append(rows);
        return out.toString().stripTrailing();
    }

    /** 单元格归一化：NULL 标记、换行替换空格、超长截断 */
    private static String cell(String value) {
        String s = value == null ? "NULL" : value.replace('\n', ' ').replace('\r', ' ');
        return s.length() > MAX_CELL_CHARS ? s.substring(0, MAX_CELL_CHARS) + "…（截断）" : s;
    }
}
