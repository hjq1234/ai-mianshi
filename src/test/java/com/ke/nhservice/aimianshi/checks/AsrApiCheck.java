package com.ke.nhservice.aimianshi.checks;

import com.ke.nhservice.aimianshi.AiMianshiApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * /api/asr/status 和 /api/asr/transcribe 的验证程序（不是 JUnit，是能直接 main 跑的）。
 *
 * 为什么非要真起服务：这两条接口真正容易错的地方都不是 Java 逻辑，而是
 * 「裸二进制 body 到底怎么进来的」——Content-Type 对不对、Spring 会不会
 * 拒绝 3.8 MB 的 application/octet-stream、采样率参数缺省值是多少。
 * 这些只有走一遍真的 HTTP 栈才验得到。
 *
 * 跑法（在仓库根目录，先 test-compile）：
 *   export JAVA_HOME="/c/Program Files/Java/jdk-21"
 *   ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q test-compile
 *   CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
 *   "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
 *     com.ke.nhservice.aimianshi.checks.AsrApiCheck D:/models/sense-voice D:/models/sense-voice/zh.wav
 *
 * 两个参数都可以省，默认就是上面那两个路径。
 * 不需要 DEEPSEEK_API_KEY：这场跑的是真 LLM 客户端，但一次都不调用它。
 */
public class AsrApiCheck {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.printf("%-4s | %s | %s%n", ok ? "PASS" : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        String modelDir = args.length > 0 ? args[0] : "D:/models/sense-voice";
        String wav = args.length > 1 ? args[1] : "D:/models/sense-voice/zh.wav";

        Path db = Path.of(System.getProperty("java.io.tmpdir"), "asrapi-" + System.nanoTime() + ".db");
        // ★ 必须走命令行参数：SpringApplicationBuilder.properties() 设的是「默认属性」，
        // 优先级低于 application.yml，server.port 和 datasource.url 都会被 yml 覆盖
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AiMianshiApplication.class)
                .run("--server.port=18090",
                        "--spring.datasource.url=jdbc:sqlite:" + db.toAbsolutePath(),
                        "--app.asr.model-dir=" + modelDir);

        try {
            HttpClient http = HttpClient.newHttpClient();

            // ── 鉴权：/api/asr 在 AuthInterceptor 的 /api/** 里，没改任何配置就该被拦 ──
            check("无 token 访问 /api/asr/status → 401",
                    statusOf(http, json("/api/asr/status").GET().build()) == 401, "");

            String loginBody = postJson(http, "/api/auth/login", null,
                    "{\"username\":\"admin\",\"password\":\"admin123\"}");
            String token = extract(loginBody, "token");
            check("admin/admin123 登录拿到 token", token != null && !token.isBlank(), "");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("登录失败，后面没法继续: " + loginBody);
            }

            // ── /status ──
            String statusBody = get(http, "/api/asr/status", token);
            check("GET /api/asr/status → code=0",
                    "0".equals(extract(statusBody, "code")), statusBody);
            check("★ data.available=true（模型目录指对了）",
                    "true".equals(extract(statusBody, "available")), statusBody);
            check("★ data.nativeVersion 非空（说明 native 库在 Spring Boot 里也加载上了）",
                    !extract(statusBody, "nativeVersion").isBlank(),
                    extract(statusBody, "nativeVersion"));
            check("★ data.maxSeconds=120（前端录音上限就用这个值）",
                    "120".equals(extract(statusBody, "maxSeconds")),
                    extract(statusBody, "maxSeconds"));

            // ── 真音频转写 ──
            byte[] wholeFileBytes = Files.readAllBytes(Path.of(wav));
            byte[] pcm = wavDataChunk(Path.of(wav));
            System.out.println("zh.wav: 整文件 " + wholeFileBytes.length + " 字节，data chunk "
                    + pcm.length + " 字节（" + (pcm.length / 2 / 16000.0) + " 秒）");

            // 这条是**确定性**的反证，不看识别结果：剥头这一步必须真的剥掉点什么。
            // （之前写的是「整文件转出来的文本和裸 PCM 不同」，那是拿识别抖动作证据 ——
            // 本次跑出来是 开饭/开放 之差，下次可能刚好相同，就成了假红）
            check("★ 剥头这步确实剥掉了东西（data chunk 比整文件短）",
                    pcm.length < wholeFileBytes.length
                            && pcm.length == wholeFileBytes.length - 44,
                    "整文件 " + wholeFileBytes.length + " → " + pcm.length
                            + "，差 " + (wholeFileBytes.length - pcm.length) + " 字节");

            String text = extract(postRaw(http, "/api/asr/transcribe?sampleRate=16000",
                    token, pcm), "text");
            System.out.println("转写结果: [" + text + "]");
            System.out.println("（对照）整文件也发一遍: ["
                    + extract(postRaw(http, "/api/asr/transcribe?sampleRate=16000",
                            token, wholeFileBytes), "text")
                    + "] —— 只是观察，不作断言：这俩的差别属于识别抖动");
            check("★ 裸 PCM 转出非空文本", !text.isBlank(), text);
            check("★ 转出的是中文",
                    text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF), text);

            // ── 采样率守卫 ──
            String wrongRate = postRaw(http, "/api/asr/transcribe?sampleRate=8000", token, pcm);
            check("★ ?sampleRate=8000 → 被拒（不是静默按 16k 解，那样只会转出一段乱码）",
                    wrongRate.contains("只支持 16000"), wrongRate);

            // ── 长度守卫 ──
            // 3,840,000 字节 = 120 秒 @16k 单声道。发得出去本身就说明 Tomcat 没在
            // 这一层挡下来（max-http-form-post-size 只管表单，application/octet-stream 不受它管）
            byte[] atLimit = new byte[120 * 16000 * 2];
            String atLimitBody = postRaw(http, "/api/asr/transcribe?sampleRate=16000", token, atLimit);
            check("★ 3.8 MB 的 body 没被 Tomcat 挡掉（不是 413）",
                    "0".equals(extract(atLimitBody, "code")),
                    atLimitBody.length() > 120 ? atLimitBody.substring(0, 120) : atLimitBody);

            byte[] overLimit = new byte[121 * 16000 * 2];
            String overBody = postRaw(http, "/api/asr/transcribe?sampleRate=16000", token, overLimit);
            check("★ 超上限（121 秒）被自己那句守卫拒掉（证明它确实经过了 controller）",
                    overBody.contains("音频太长"), overBody);

            // ── 空 body ──
            // 这条一开始是**假绿**的：断言写成了 `contains("400") || contains("code")`，
            // 而实际响应是 code=500 + 「服务器内部错误：Required request body is missing…」。
            // 原因见 AsrController 里那个 required = false。现在断言收紧到「必须是我们那句话」
            String empty = postRaw(http, "/api/asr/transcribe?sampleRate=16000", token, new byte[0]);
            check("★ 空 body 回的是我们那句「没有收到音频数据」，不是 500、不是框架英文",
                    empty.contains("没有收到音频数据") && !empty.contains("服务器内部错误"), empty);

        } finally {
            ctx.close();
        }

        // ── 没配模型目录那一场：要回一句人话，不是 500 ──
        Path db2 = Path.of(System.getProperty("java.io.tmpdir"), "asrapi-nomodel-" + System.nanoTime() + ".db");
        Path emptyDir = Files.createTempDirectory("asr-empty-");
        ConfigurableApplicationContext ctx2 = new SpringApplicationBuilder(AiMianshiApplication.class)
                .run("--server.port=18091",
                        "--spring.datasource.url=jdbc:sqlite:" + db2.toAbsolutePath(),
                        "--app.asr.model-dir=" + emptyDir.toAbsolutePath());

        try {
            HttpClient http = HttpClient.newHttpClient();
            // 起得来这件事本身就有意义：模型没下的人也能把服务跑起来
            System.out.println("（服务在模型目录为空时也起来了 —— 这就是设计要的「没模型不影响启动」）");

            String token = extract(postJson("http://localhost:18091", http, "/api/auth/login", null,
                    "{\"username\":\"admin\",\"password\":\"admin123\"}"), "token");

            String s = get("http://localhost:18091", http, "/api/asr/status", token);
            check("★ 模型目录为空时 available=false", "false".equals(extract(s, "available")), s);
            check("★ 且 reason 是句人话（不是 null、不是异常堆栈）",
                    !extract(s, "reason").isBlank(), extract(s, "reason"));
            check("★ 但 nativeVersion 仍然拿得到（库和模型是两件事）",
                    !extract(s, "nativeVersion").isBlank(), extract(s, "nativeVersion"));

            String t = postRaw("http://localhost:18091", http, "/api/asr/transcribe?sampleRate=16000",
                    token, new byte[32000]);
            check("★ 没模型时转写请求回「语音识别不可用」+ 原因，不是 500",
                    t.contains("语音识别不可用"), t);
        } finally {
            ctx2.close();
        }

        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 从 wav 里剥出 data chunk 的裸 PCM。
     *
     * 不硬编码「跳过 44 字节头」：头长度取决于有没有 LIST/INFO 之类的额外块，
     * 换了文件就不再是 44。zh.wav 正好是 44，所以调用方顺带断言了这个数 ——
     * 换个 wav 跑时那条断言会红，提醒你看一眼是不是该改，而不是静默发错东西。
     */
    static byte[] wavDataChunk(Path wav) throws Exception {
        byte[] all = Files.readAllBytes(wav);
        int i = 12;   // 跳过 "RIFF" + 4 字节长度 + "WAVE"
        while (i + 8 <= all.length) {
            String id = new String(all, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (all[i + 4] & 0xFF) | ((all[i + 5] & 0xFF) << 8)
                    | ((all[i + 6] & 0xFF) << 16) | ((all[i + 7] & 0xFF) << 24);
            if ("data".equals(id)) {
                return Arrays.copyOfRange(all, i + 8, Math.min(all.length, i + 8 + size));
            }
            // 块之间按偶数字节对齐
            i += 8 + size + (size % 2);
        }
        throw new IllegalStateException("这个 wav 里找不到 data chunk：" + wav);
    }

    // ────────────────────────── HTTP 小工具 ──────────────────────────

    static final String BASE = "http://localhost:18090";

    static HttpRequest.Builder json(String path) {
        return json(BASE, path);
    }

    static HttpRequest.Builder json(String base, String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
    }

    static int statusOf(HttpClient http, HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static String postJson(HttpClient http, String path, String token, String body) throws Exception {
        return postJson(BASE, http, path, token, body);
    }

    static String postJson(String base, HttpClient http, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder b = json(base, path);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * 裸二进制 POST。Content-Type 必须是 application/octet-stream ——
     * 和浏览器里 asr.js 走的是同一条分支。
     */
    static String postRaw(HttpClient http, String path, String token, byte[] body) throws Exception {
        return postRaw(BASE, http, path, token, body);
    }

    static String postRaw(String base, HttpClient http, String path, String token, byte[] body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/octet-stream");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String get(HttpClient http, String path, String token) throws Exception {
        return get(BASE, http, path, token);
    }

    static String get(String base, HttpClient http, String path, String token) throws Exception {
        HttpRequest.Builder b = json(base, path);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 从 JSON 里取字段原始值。只用于断言，方法名冲突时取先出现的那个 */
    static String extract(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int start = i + needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = start + 1;
            StringBuilder sb = new StringBuilder();
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\' && end + 1 < json.length()) {
                    sb.append(json.charAt(end + 1));
                    end += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
                end++;
            }
            return sb.toString();
        }
        int end = start;
        while (end < json.length() && ",}\n".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(start, end).trim();
    }
}