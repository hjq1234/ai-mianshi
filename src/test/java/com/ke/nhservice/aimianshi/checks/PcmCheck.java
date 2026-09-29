package com.ke.nhservice.aimianshi.checks;

import com.ke.nhservice.aimianshi.wrapper.asr.PcmCodec;

import java.util.Arrays;

/**
 * 字节序验证程序（不是 JUnit 测试，是能直接 main 跑的检查脚本）。
 *
 * 为什么要单独一个程序盯字节序：小端写成大端**不抛异常**，只会转出一段
 * 看着像话、其实全错的文本。这种错不能靠「转写能出字」顺带验。
 *
 * 跑法（在仓库根目录）：
 *   export JAVA_HOME="/c/Program Files/Java/jdk-21"
 *   ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q test-compile
 *   "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 \
 *     -cp "target/classes;target/test-classes" \
 *     com.ke.nhservice.aimianshi.checks.PcmCheck
 */
public class PcmCheck {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.printf("%-4s | %s | %s%n", ok ? "PASS" : "FAIL", name, detail);
    }

    public static void main(String[] args) {
        // 0x4000 = 16384 → 0.5
        check("小端 {0x00,0x40} → 0.5",
                Math.abs(PcmCodec.toFloats(new byte[]{0x00, 0x40})[0] - 0.5f) < 1e-6,
                Arrays.toString(PcmCodec.toFloats(new byte[]{0x00, 0x40})));

        // 0x7FFF = 32767 → 约 1.0（不是 1.0，32767/32768）
        check("小端正最大 {0xFF,0x7F} → ≈1.0",
                Math.abs(PcmCodec.toFloats(new byte[]{(byte) 0xFF, 0x7F})[0] - 0.99997f) < 1e-4,
                Arrays.toString(PcmCodec.toFloats(new byte[]{(byte) 0xFF, 0x7F})));

        // 0x8000 = -32768 → -1.0。这条同时验符号位
        check("小端负满量程 {0x00,0x80} → -1.0",
                PcmCodec.toFloats(new byte[]{0x00, (byte) 0x80})[0] == -1.0f,
                Arrays.toString(PcmCodec.toFloats(new byte[]{0x00, (byte) 0x80})));

        check("0 → 0.0", PcmCodec.toFloats(new byte[]{0x00, 0x00})[0] == 0.0f, "");

        // 奇数长度：丢掉落单的那个字节，不能抛异常也不能错位
        float[] odd = PcmCodec.toFloats(new byte[]{0x00, 0x40, 0x7F});
        check("奇数长度只取整字节，长度 1",
                odd.length == 1 && Math.abs(odd[0] - 0.5f) < 1e-6, "长度=" + odd.length);

        check("空数组不抛异常", PcmCodec.toFloats(new byte[0]).length == 0, "");

        // 反证：如果实现写成了大端，上面第 1 条会得到 0.00195（64/32768）而不是 0.5。
        // 显式算一遍大端的结果，确认它确实不等于 0.5 —— 免得哪天两边一起改错、检查还绿
        short bigEndian = (short) ((0x00 << 8) | (0x40 & 0xFF));
        check("反证：大端解出来是 0.00195，不是 0.5（两个值确实不同）",
                Math.abs(bigEndian / 32768.0f - 0.5f) > 1e-3, "大端=" + (bigEndian / 32768.0f));

        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}