package com.markagent.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能内嵌 shell 执行器：解析技能正文中的两种语法——
 * 行内 !`command` 与围栏块 ```! ... ```（info string 恰为 "!"），执行后把输出替换回正文。
 * <p>
 * 双开关：全局 markagent.skills.shell-enabled=true 且技能 frontmatter 声明了 shell 字段，
 * 任一不满足则不执行、占位符原样保留。命令失败/超时/异常降级为「[命令执行失败: 原因]」文本，
 * 不中断整体调用；每次执行写审计日志（技能名/命令/退出码）。
 */
@Component
public class SkillShellRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillShellRunner.class);

    /** 输出上限 8KB（字符），超出截断加「…」 */
    private static final int MAX_OUTPUT_LENGTH = 8192;

    /** 默认超时 30 秒 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** 同时匹配两种语法：围栏块 ```! ... ```（组 1）或行内 !`cmd`（组 2） */
    private static final Pattern SHELL_BLOCK = Pattern.compile(
            "```[ \\t]*![ \\t]*\\R(.*?)\\R```[ \\t]*(?=\\R|$)|!`([^`\\r\\n]+)`",
            Pattern.DOTALL);

    private final boolean shellEnabled;
    private final Duration timeout;

    /** 生产装配：全局开关 markagent.skills.shell-enabled（默认关闭）。
     *  多构造器共存需显式 @Autowired，否则 Spring 找不到无参构造器而装配失败 */
    @Autowired
    public SkillShellRunner(@Value("${markagent.skills.shell-enabled:false}") boolean shellEnabled) {
        this(shellEnabled, DEFAULT_TIMEOUT);
    }

    /** 包内构造：供测试缩短超时 */
    SkillShellRunner(boolean shellEnabled, Duration timeout) {
        this.shellEnabled = shellEnabled;
        this.timeout = timeout;
    }

    /**
     * 替换正文中的内嵌 shell 占位符。全局开关关闭或技能未声明 shell 时原样返回。
     *
     * @param skillName 技能名（审计日志用）
     * @param content   技能正文（已经过参数/变量替换）
     * @param shell     技能声明的 shell 类型（bash/powershell/cmd，null=禁用）
     * @param skillDir  命令工作目录（技能目录）
     */
    public String run(String skillName, String content, String shell, Path skillDir) {
        if (!shellEnabled || shell == null || shell.isBlank()) {
            return content;
        }
        Matcher matcher = SHELL_BLOCK.matcher(content);
        StringBuilder sb = new StringBuilder(content.length());
        while (matcher.find()) {
            String command = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String output = execute(skillName, command, shell, skillDir);
            // quoteReplacement：输出中的 $ 与 \ 按字面替换，防止 appendReplacement 组引用转义
            matcher.appendReplacement(sb, Matcher.quoteReplacement(output));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 执行单条命令：stdout+stderr 合并、截断 8KB；失败/超时/异常返回降级文本 */
    private String execute(String skillName, String command, String shell, Path skillDir) {
        String[] cmd = switch (shell.toLowerCase(Locale.ROOT)) {
            case "bash" -> new String[]{"bash", "-c", command};
            case "powershell" -> new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command", command};
            case "cmd" -> new String[]{"cmd", "/c", command};
            default -> null;
        };
        if (cmd == null) {
            log.info("技能 {} 内嵌 shell 未执行：不支持的 shell 类型 [{}]", skillName, shell);
            return "[命令执行失败: 不支持的 shell 类型: " + shell + "]";
        }
        Process process = null;
        try {
            process = new ProcessBuilder(cmd)
                    .directory(skillDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.info("技能 {} 内嵌 shell 执行超时：命令=[{}]，已强制终止", skillName, command);
                return "[命令执行失败: 超时（" + timeout.toSeconds() + " 秒）]";
            }
            int exitCode = process.exitValue();
            log.info("技能 {} 内嵌 shell 执行完成：命令=[{}]，退出码={}", skillName, command, exitCode);
            if (exitCode != 0) {
                return "[命令执行失败: 退出码 " + exitCode + "]";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return truncate(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "[命令执行失败: 执行被中断]";
        } catch (IOException e) {
            return "[命令执行失败: " + e.getMessage() + "]";
        }
    }

    private static String truncate(String output) {
        if (output.length() > MAX_OUTPUT_LENGTH) {
            return output.substring(0, MAX_OUTPUT_LENGTH) + "…";
        }
        return output;
    }
}
