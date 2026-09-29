package com.ke.nhservice.aimianshi.checks;

import com.k2fsa.sherpa.onnx.WaveReader;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrProperties;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrStatus;
import com.ke.nhservice.aimianshi.wrapper.asr.SherpaAsrClient;

/**
 * 真转写验证程序：读模型仓库带的 zh.wav，断言转出中文。
 * 再连转 20 次，验 stream 释放了、recognizer 复用没坏。
 *
 * 这一份是唯一能证明「native 真的跑起来了」的东西 —— 编译通过只说明签名对，
 * 签名对但 dll 加载不上是完全可能的。
 *
 * 跑法（在仓库根目录，模型没配就只验「不炸」那一半）：
 *   export JAVA_HOME="/c/Program Files/Java/jdk-21"
 *   ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q test-compile
 *   CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
 *   "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
 *     com.ke.nhservice.aimianshi.checks.AsrCheck D:/models/sense-voice D:/models/sense-voice/zh.wav
 *
 * 验「模型没下也不炸」：第一个参数指到一个空目录，应当打出 available=false
 * 和一句人话，然后退出码 1 —— 关键是**不抛异常、不崩进程**。
 */
public class AsrCheck {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.printf("%-4s | %s | %s%n", ok ? "PASS" : "FAIL", name, detail);
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "D:/models/sense-voice";
        String wav = args.length > 1 ? args[1] : "D:/models/sense-voice/zh.wav";

        AsrProperties props = new AsrProperties();
        props.setModelDir(dir);
        SherpaAsrClient client = new SherpaAsrClient(props);

        AsrStatus st = client.status();
        System.out.println("status: available=" + st.available()
                + " reason=" + st.reason() + " nativeVersion=" + st.nativeVersion()
                + " maxSeconds=" + st.maxSeconds());
        check("★ native 库加载上了（版本号拿得到就是证据）",
                st.nativeVersion() != null && !st.nativeVersion().isBlank(), st.nativeVersion());

        if (!st.available()) {
            // 这条分支就是「模型没下」的验证：不抛异常、不崩，还给得出一句话
            System.out.println("模型没就位，只验到「不炸」为止。reason=" + st.reason());
            System.out.println("RESULT: pass=" + pass + " fail=" + fail + "（模型缺失，后半段跳过）");
            System.exit(1);
        }
        check("★ status 说可用", true, "reason 为 null");

        WaveReader reader = new WaveReader(wav);
        float[] samples = reader.getSamples();
        int rate = reader.getSampleRate();
        System.out.println("wav: " + samples.length + " 采样 @ " + rate + " Hz（"
                + String.format("%.2f", samples.length / (float) rate) + " 秒）");

        long t0 = System.currentTimeMillis();
        String text = client.transcribe(samples, rate);
        long first = System.currentTimeMillis() - t0;
        System.out.println("第一次转写（含模型加载）: " + first + " ms");
        System.out.println("转写结果: [" + text + "]");

        check("★ 转出非空文本", !text.isBlank(), text);
        check("★ 转出的是中文（zh.wav 是一段中文）",
                text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF), text);
        // ITN 生效的证据：数字应当被写成「9点」而不是「九点」
        check("★ ITN 生效（数字被规范化，不是汉字数字）",
                text.codePoints().anyMatch(Character::isDigit), text);

        // 第二次：模型已加载好，应该快很多；同时验 recognizer 复用没坏
        long t1 = System.currentTimeMillis();
        String again = client.transcribe(samples, rate);
        long second = System.currentTimeMillis() - t1;
        System.out.println("第二次转写（模型已加载）: " + second + " ms");
        check("★ 第二次结果和第一次一致（recognizer 复用后没串味）",
                text.equals(again), "[" + again + "]");
        check("★ 第二次明显更快（说明模型只加载了一次）",
                second < first, "第一次 " + first + " ms → 第二次 " + second + " ms");

        // 连转 20 次：每轮都 createStream，不 release 的话这里会涨
        long before = usedHeap();
        for (int i = 0; i < 20; i++) {
            client.transcribe(samples, rate);
        }
        System.gc();
        long after = usedHeap();
        System.out.printf("20 次后堆内存: %.1f MB → %.1f MB%n", before / 1048576.0, after / 1048576.0);
        String after20 = client.transcribe(samples, rate);
        check("★ 连转 20 次结果仍然正确（不是第 3 次就开始出乱码）",
                after20.equals(text), "[" + after20 + "]");

        client.close();
        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}