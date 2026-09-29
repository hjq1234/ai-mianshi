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
 * /api/asr/stream/* 的验证程序（不是 JUnit，是能直接 main 跑的）。
 *
 * 为什么必须真起服务 + 真喂音频：这条路上容易错的没有一处是 Java 逻辑 ——
 * 「native 库里那个 OnlineRecognizer 到底认不认这个模型」「isEndpoint 什么时候触发」
 * 「stop 时最后那片解没解」，全是 native 行为，只有真跑一遍才知道。
 *
 * 跑法（仓库根目录，先 test-compile）：
 *   export JAVA_HOME="/c/Program Files/Java/jdk-21"
 *   ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q test-compile
 *   CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
 *   "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
 *     com.ke.nhservice.aimianshi.checks.AsrStreamCheck
 *
 * 两个参数都可以省。默认用**这个模型自带的 test_wavs/0.wav**，不蹭 SenseVoice 那个：
 * 流式模型的测试音频是配套的，出问题时不用先怀疑「是不是音频本来就不对」
 */
public class AsrStreamCheck {

    static int pass = 0, fail = 0;
    static final String BASE = "http://127.0.0.1:18092";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.printf("%-4s | %s | %s%n", ok ? "PASS" : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        String modelDir = args.length > 0 ? args[0]
                : "D:/models/streaming-zh-14M";
        // 模型自带的测试音频。5.6 秒的中文，切成 100ms 一片正好 56 片
        String wav = args.length > 1 ? args[1]
                : "D:/models/streaming-zh-14M/test_wavs/0.wav";

        Path db = Path.of(System.getProperty("java.io.tmpdir"), "asrstream-" + System.nanoTime() + ".db");
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AiMianshiApplication.class)
                .run("--server.port=18092",
                        "--spring.datasource.url=jdbc:sqlite:" + db.toAbsolutePath(),
                        "--app.asr.model-dir=",
                        "--app.asr.stream.model-dir=" + modelDir);

        try {
            HttpClient http = HTTP;
            String token = extract(post(BASE, http, "/api/auth/login", null,
                    "{\"username\":\"admin\",\"password\":\"admin123\"}",
                    "application/json"), "token");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("登录失败");
            }

            String status = get(http, "/api/asr/status", token);
            check("★ /status 里 streamAvailable=true（流式模型目录指对了）",
                    "true".equals(extract(status, "streamAvailable")), status);
            check("★ 离线那条报 available=false（本次没配它）—— 两条路各报各的，没被互相带偏",
                    "false".equals(extract(status, "available")), status);

            byte[] pcm = wavDataChunk(Path.of(wav));
            int chunkBytes = 16000 * 2 / 10;    // 100ms 一片
            int total = pcm.length / chunkBytes;
            System.out.println("0.wav: " + pcm.length + " 字节 = "
                    + (pcm.length / 2 / 16000.0) + " 秒，切成 " + total + " 片");

            String started = post(BASE, http, "/api/asr/stream/start", token, null, "application/json");
            String sid = extract(started, "sessionId");
            check("★ /stream/start 拿到 sessionId", sid != null && !sid.isBlank(), started);
            if (sid == null || sid.isBlank()) {
                throw new IllegalStateException("没拿到会话，后面没法继续: " + started);
            }

            // ── 核心：一片一片喂，看第几片开始出字 ──
            // 这条断言是整个检查的意义所在：断「最后能转出正确文本」是没用的 ——
            // 把整段攒起来一次性解码也照样绿。只有「喂到一半就有字」能证明它真是流式
            int firstTextAt = -1;
            String sawFinal = "";
            StringBuilder partials = new StringBuilder();
            for (int i = 0; i < total; i++) {
                byte[] slice = Arrays.copyOfRange(pcm, i * chunkBytes, (i + 1) * chunkBytes);
                String r = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid
                        + "&sampleRate=16000", token, slice);
                String partial = extract(r, "partial");
                String fin = extract(r, "finalText");
                if (firstTextAt < 0 && !partial.isBlank()) {
                    firstTextAt = i;
                }
                if (!fin.isBlank()) {
                    sawFinal = fin;
                    System.out.println("  第 " + i + " 片定稿: [" + fin + "]");
                }
                if (partials.length() < 200) {
                    partials.append(partial).append('|');
                }
            }
            System.out.println("半句的演变: " + partials);
            check("★ **喂到一半（第 " + firstTextAt + " 片 / 共 " + total + " 片）就已经出字了**"
                            + " —— 这才叫流式。攒够整段再解码的话这里必然是 -1",
                    firstTextAt > 0 && firstTextAt < total / 2, "firstTextAt=" + firstTextAt);

            String stopped = post(BASE, http, "/api/asr/stream/stop?sessionId=" + sid,
                    token, null, "application/json");
            String finalText = extract(stopped, "finalText");
            System.out.println("stop 返回: [" + finalText + "]");
            check("★ /stream/stop 返回最后那半句（不是空）", !finalText.isBlank(), finalText);
            check("★ 拼起来是中文",
                    (sawFinal + finalText).codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF),
                    sawFinal + " + " + finalText);

            // ── 停顿定稿（isEndpoint → reset 那条路）──
            // 上面那 5.6 秒是一整句连着说的，中间没有 1.5 秒的停顿，所以**一次都没定稿**，
            // 全堆在 stop() 里才出来。而「灰字行定稿后追加进答题框」靠的正是这条路径 ——
            // 不单独验一次的话，SherpaStreamAsrClient.chunk 里那两行的顺序写反了
            // （先 reset 再取结果 → 拿到的是空串）也照样全绿，表现是灰字行永远不定稿。
            String sid3 = extract(post(BASE, http, "/api/asr/stream/start", token, null, "application/json"),
                    "sessionId");
            String settled = "";
            int settledAfterSilenceAt = -1;
            for (int i = 0; i < total; i++) {
                String r = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid3 + "&sampleRate=16000",
                        token, Arrays.copyOfRange(pcm, i * chunkBytes, (i + 1) * chunkBytes));
                if (!extract(r, "finalText").isBlank()) {
                    settled = extract(r, "finalText");
                }
            }
            // 说完灌 2 秒静音：rule1 的 1.5 秒尾静音会在这里触发断句
            for (int i = 0; i < 20; i++) {
                String r = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid3 + "&sampleRate=16000",
                        token, new byte[chunkBytes]);
                if (!extract(r, "finalText").isBlank()) {
                    settled = extract(r, "finalText");
                    settledAfterSilenceAt = i;
                }
            }
            check("★ 说完灌 2 秒静音 → 触发断句，finalText 在**没调 stop 之前**就返回了",
                    !settled.isBlank(), "第 " + settledAfterSilenceAt + " 片静音时定稿：[" + settled + "]");
            String tail = extract(post(BASE, http, "/api/asr/stream/stop?sessionId=" + sid3, token,
                    null, "application/json"), "finalText");
            check("★ 定稿之后 stop 回空 —— 同一句不会被追加进框两遍", tail.isBlank(), "[" + tail + "]");

            // ── 会话生命周期 ──
            String again = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid
                    + "&sampleRate=16000", token, new byte[chunkBytes]);
            check("★ stop 之后再喂同一会话 → 过期了，不是 500",
                    again.contains("会话已经过期"), again);

            String lateStop = post(BASE, http, "/api/asr/stream/stop?sessionId=" + sid,
                    token, null, "application/json");
            check("★ 对已经结束的会话再 stop → 回空文本，不给用户一个红条",
                    "0".equals(extract(lateStop, "code")) && extract(lateStop, "finalText").isBlank(),
                    lateStop);

            // ── 守卫 ──
            String noSession = post(BASE, http, "/api/asr/stream/start", token, null, "application/json");
            String sid2 = extract(noSession, "sessionId");
            String wrongRate = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid2
                    + "&sampleRate=8000", token, new byte[chunkBytes]);
            check("★ ?sampleRate=8000 → 被拒（静默按 16k 解只会转出一段乱码）",
                    wrongRate.contains("只支持 16000"), wrongRate);
            String empty = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid2
                    + "&sampleRate=16000", token, new byte[0]);
            check("★ 空 body 回的是我们那句「没有收到音频数据」，不是 500、不是框架英文",
                    empty.contains("没有收到音频数据") && !empty.contains("服务器内部错误"), empty);
            post(BASE, http, "/api/asr/stream/stop?sessionId=" + sid2, token, null, "application/json");

            check("★ 无 token 访问 /api/asr/stream/start → 401",
                    statusOf(raw("/api/asr/stream/start", null, new byte[0])
                            .POST(HttpRequest.BodyPublishers.noBody()).build()) == 401, "");

        } finally {
            ctx.close();
        }

        // ── 流式模型没配时：要回一句人话，且不影响离线那条 ──
        Path db2 = Path.of(System.getProperty("java.io.tmpdir"), "asrstream-no-" + System.nanoTime() + ".db");
        Path emptyDir = Files.createTempDirectory("asrstream-empty-");
        ConfigurableApplicationContext ctx2 = new SpringApplicationBuilder(AiMianshiApplication.class)
                .run("--server.port=18093",
                        "--spring.datasource.url=jdbc:sqlite:" + db2.toAbsolutePath(),
                        "--app.asr.model-dir=D:/models/sense-voice",
                        "--app.asr.stream.model-dir=" + emptyDir.toAbsolutePath());
        try {
            HttpClient http = HTTP;
            String token = extract(post("http://127.0.0.1:18093", http, "/api/auth/login", null,
                    "{\"username\":\"admin\",\"password\":\"admin123\"}",
                    "application/json"), "token");

            String s = get("http://127.0.0.1:18093", http, "/api/asr/status", token);
            check("★ 流式模型目录指向一个空目录 → streamAvailable=false 且 streamReason 是句人话",
                    "false".equals(extract(s, "streamAvailable"))
                            && !extract(s, "streamReason").isBlank(),
                    extract(s, "streamReason"));
            check("★ 但同一次请求里 available 仍是 true —— 流式没配不该把离线也拖下水",
                    "true".equals(extract(s, "available")), s);

            String st = post("http://127.0.0.1:18093", http, "/api/asr/stream/start", token,
                    null, "application/json");
            check("★ 没配流式时 start 回「流式语音识别不可用」+ 原因，不是 500",
                    st.contains("流式语音识别不可用"), st);
        } finally {
            ctx2.close();
        }

        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ── HTTP 工具：直接抄 AsrApiCheck 里那几段（同一个项目的另一份检查，形状保持一致） ──

    static HttpRequest.Builder raw(String path, String token, byte[] body) {
        return raw(BASE, path, token, body);
    }

    static HttpRequest.Builder raw(String base, String path, String token, byte[] body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/octet-stream");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    static String postRaw(HttpClient http, String path, String token, byte[] body) throws Exception {
        return postRaw(BASE, http, path, token, body);
    }

    static String postRaw(String base, HttpClient http, String path, String token, byte[] body)
            throws Exception {
        return http.send(raw(base, path, token, body).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String base, HttpClient http, String path, String token, String body,
                       String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", contentType);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String get(String base, HttpClient http, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String get(HttpClient http, String path, String token) throws Exception {
        return get(BASE, http, path, token);
    }

    static int statusOf(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static byte[] wavDataChunk(Path wav) throws Exception {
        byte[] all = Files.readAllBytes(wav);
        int i = 12;
        while (i + 8 <= all.length) {
            String id = new String(all, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (all[i + 4] & 0xFF) | ((all[i + 5] & 0xFF) << 8)
                    | ((all[i + 6] & 0xFF) << 16) | ((all[i + 7] & 0xFF) << 24);
            if ("data".equals(id)) {
                return Arrays.copyOfRange(all, i + 8, Math.min(all.length, i + 8 + size));
            }
            i += 8 + size + (size % 2);
        }
        throw new IllegalStateException("这个 wav 里找不到 data chunk：" + wav);
    }

    /**
     * 从 JSON 里取字段原始值。只用于断言，同名 key 取**先出现**的那个。
     *
     * 正因为这样，AsrStatusVO 里 streamAvailable / streamReason 是**平铺**的：
     * 嵌套成 stream:{available:...} 的话，这里取 "available" 就会碰到两个，
     * 哪天字段顺序一变断言测的就是另一个东西 —— 而且它是绿的。
     */
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