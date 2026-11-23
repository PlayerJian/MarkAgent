package com.markagent.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** SkillShellRunner 单测：本地 cmd 进程执行（不联网）；非 Windows 平台跳过真实 shell 用例 */
class SkillShellRunnerTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void 全局开关关闭时不执行占位符原样保留(@TempDir Path dir) {
        SkillShellRunner runner = new SkillShellRunner(false);
        String content = "你好!`echo hello`世界";
        assertEquals(content, runner.run("s", content, "cmd", dir));
    }

    @Test
    void 技能未声明shell时不执行(@TempDir Path dir) {
        SkillShellRunner runner = new SkillShellRunner(true);
        String content = "你好!`echo hello`世界";
        assertEquals(content, runner.run("s", content, null, dir));
        assertEquals(content, runner.run("s", content, "", dir));
    }

    @Test
    void cmd行内命令替换为输出(@TempDir Path dir) {
        assumeTrue(isWindows(), "需 Windows cmd");
        SkillShellRunner runner = new SkillShellRunner(true);
        assertEquals("日期是 2026-11-10。", runner.run("s", "日期是 !`echo 2026-11-10`。", "cmd", dir));
    }

    @Test
    void cmd围栏块命令替换为输出(@TempDir Path dir) {
        assumeTrue(isWindows(), "需 Windows cmd");
        SkillShellRunner runner = new SkillShellRunner(true);
        assertEquals("前置fenced\n后置", runner.run("s", "前置```!\necho fenced\n```\n后置", "cmd", dir));
    }

    @Test
    void 命令失败降级为占位文本(@TempDir Path dir) {
        assumeTrue(isWindows(), "需 Windows cmd");
        SkillShellRunner runner = new SkillShellRunner(true);
        assertEquals("前[命令执行失败: 退出码 7]后", runner.run("s", "前!`exit 7`后", "cmd", dir));
    }

    @Test
    void 超时强制终止并降级() {
        assumeTrue(isWindows(), "需 Windows cmd");
        // 工作目录用系统临时目录：被强制终止的 ping 子进程仍持有原工作目录句柄，
        // 若用 @TempDir 会导致目录清理失败
        Path dir = Path.of(System.getProperty("java.io.tmpdir"));
        SkillShellRunner runner = new SkillShellRunner(true, Duration.ofSeconds(1));
        String result = runner.run("s", "!`ping -n 5 127.0.0.1`", "cmd", dir);
        assertTrue(result.contains("[命令执行失败: 超时"), result);
    }

    @Test
    void 输出中的美元符号不污染替换(@TempDir Path dir) {
        assumeTrue(isWindows(), "需 Windows cmd");
        SkillShellRunner runner = new SkillShellRunner(true);
        assertEquals("值：$HOME结束", runner.run("s", "值：!`echo $HOME`结束", "cmd", dir));
    }

    @Test
    void 未知shell类型降级(@TempDir Path dir) {
        SkillShellRunner runner = new SkillShellRunner(true);
        assertEquals("[命令执行失败: 不支持的 shell 类型: fish]",
                runner.run("s", "!`echo x`", "fish", dir));
    }

    @Test
    void 多段命令分别替换(@TempDir Path dir) {
        assumeTrue(isWindows(), "需 Windows cmd");
        SkillShellRunner runner = new SkillShellRunner(true);
        assertEquals("a hello b world c", runner.run("s", "a !`echo hello` b !`echo world` c", "cmd", dir));
    }
}
