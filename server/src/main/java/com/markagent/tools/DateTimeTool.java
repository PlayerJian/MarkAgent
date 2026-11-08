package com.markagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 日期时间工具：当前时间、指定时区时间、日期差/时间差计算。
 */
@Component
public class DateTimeTool {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(name = "now", description = "获取当前日期时间。可选参数 timezone（IANA 时区名，如 Asia/Shanghai、UTC、America/New_York），缺省为系统时区。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String now(@ToolParam(name = "timezone", required = false,
            description = "IANA 时区名，如 Asia/Shanghai、UTC、America/New_York；缺省为系统时区") String timezone) {
        try {
            ZoneId zone = timezone == null || timezone.isBlank()
                    ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
            ZonedDateTime now = ZonedDateTime.now(zone);
            return String.format(Locale.ROOT, "%s %s（%s，%s）", now.format(TS), now.getZone().getId(),
                    now.getDayOfWeek(), weekChinese(now.getDayOfWeek()));
        } catch (Exception e) {
            return "错误：无效时区 '" + timezone + "'";
        }
    }

    @Tool(name = "date_diff", description = "计算两个日期之间的天数差（date2 - date1）。日期格式 yyyy-MM-dd。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String dateDiff(
            @ToolParam(name = "date1", required = true, description = "起始日期 yyyy-MM-dd") String date1,
            @ToolParam(name = "date2", required = true, description = "结束日期 yyyy-MM-dd") String date2) {
        try {
            LocalDate d1 = LocalDate.parse(date1.trim());
            LocalDate d2 = LocalDate.parse(date2.trim());
            long days = Duration.between(d1.atStartOfDay(), d2.atStartOfDay()).toDays();
            return "相差 " + days + " 天（" + d1 + " 到 " + d2 + "）";
        } catch (DateTimeParseException e) {
            return "错误：日期格式应为 yyyy-MM-dd";
        }
    }

    @Tool(name = "time_diff", description = "计算两个时刻之间的小时数（t2 - t1）。格式 yyyy-MM-dd HH:mm:ss（可省略日期，视为今天）。",
            strict = true, readOnly = true, concurrencySafe = true)
    public String timeDiff(
            @ToolParam(name = "t1", required = true, description = "起始时刻 yyyy-MM-dd HH:mm:ss 或 HH:mm:ss") String t1,
            @ToolParam(name = "t2", required = true, description = "结束时刻 yyyy-MM-dd HH:mm:ss 或 HH:mm:ss") String t2) {
        try {
            LocalDateTime a = parseLoose(t1.trim());
            LocalDateTime b = parseLoose(t2.trim());
            Duration duration = Duration.between(a, b);
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            return String.format(Locale.ROOT, "相差 %d 小时 %d 分钟", hours, minutes);
        } catch (DateTimeParseException e) {
            return "错误：时刻格式应为 yyyy-MM-dd HH:mm:ss 或 HH:mm:ss";
        }
    }

    private static LocalDateTime parseLoose(String text) {
        try {
            return LocalDateTime.parse(text, TS);
        } catch (DateTimeParseException e) {
            return LocalDate.now().atTime(LocalTime.parse(text, DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
    }

    private static String weekChinese(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
