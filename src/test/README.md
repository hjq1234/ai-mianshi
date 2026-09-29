# 验证程序

这里放的不是 JUnit 测试用例，是**能直接 `main` 跑的验证程序**：需要确认某件事的时候
写一个、跑一次、看输出。

为什么要放在仓库里而不是临时目录：验证程序里存着「这件事当时是怎么被确认过的」。
换个会话、换个人，能直接跑一遍复查；放在 `%TEMP%` 里清一次就没了。

约定：

- Java 的放 `src/test/java/com/ke/nhservice/aimianshi/checks/`，每个都是独立
  `public static void main`，打印 `PASS` / `FAIL` 行，失败时 `System.exit(1)`
- 前端的放 `src/test/js/`，用 node 直接跑，不需要 `npm install`
- **不引 JUnit、不加 `@Test`**。`AiMianshiApplicationTests`（Spring Initializr 自带那个）
  是仓库里唯一的 JUnit 类，保持原样
- 断言里带 `★` 的是「这条错了就是真出事了」，其余是上下文或反证

---

## 共同前提

JDK 21 不在这台机器的 PATH 上，每条构建命令都得先设 `JAVA_HOME`：

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
```

`~/.m2/settings.xml` 不存在，`-s /d/apache-jmeter-5.4.3/settings.xml` 是唯一的配置来源。

本地仓库也要显式指：`-Dmaven.repo.local=D:/repository`。不指的话命令行落在
`C:\Users\<你>\.m2\repository`，而 IDEA 读 `D:\repository` —— 两个不是同一个仓库，
手工 `install:install-file` 装进一个、另一个就看不见（语音那两个 sherpa jar 就是这么踩的）。
本节所有 `mvnw` 命令都该带上这个参数。

---

## Java 验证程序

先编译测试代码：

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q test-compile
```

`checks/` 下的程序分两类，跑法不同。

### 一、不依赖 Spring 容器的（直接 `java -cp`）

`target/classes;target/test-classes` 两个目录就够。

```bash
CP="target/classes;target/test-classes"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
  com.ke.nhservice.aimianshi.checks.PcmCheck
```

| 程序 | 验什么 | 需要什么 |
|---|---|---|
| `PcmCheck` | PCM16 **小端**字节序。小端写成大端不抛异常，只会转出一段看着像话、其实全错的文本，所以必须单独验 | 无 |
| `AsrCheck` | sherpa-onnx native 真的跑起来了：加载模型、转 `zh.wav` 出中文、连转 20 次结果不坏。**唯一能证明 dll 加载成功的东西** —— 编译通过只说明签名对 | 模型目录 + `zh.wav` |

```bash
export APP_ASR_MODEL_DIR=D:/models/sense-voice
CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
  com.ke.nhservice.aimianshi.checks.AsrCheck
```

`AsrCheck` 把第一个参数指到一个**空目录**，就是「模型没下也不崩」那条验证：
应当打出 `available=false` 和一句人话，退出码 1，**不抛异常、不崩进程**。

### 二、要起 Spring 容器的（`-cp` 里得带上全部依赖）

```bash
CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
  com.ke.nhservice.aimianshi.checks.AsrApiCheck [模型目录] [zh.wav]
```

`$TEMP/cp.txt` 是依赖清单，maven 的 `dependency:build-classpath` 生成一次即可。
`AsrApiCheck` 验的是「裸二进制 body 到底怎么进来的」这类只有走真 HTTP 栈才看得到的东西：
401 拦截、`available`/`nativeVersion`/`maxSeconds`、剥 wav 头、
`sampleRate=8000` 拒绝、3.8 MB body 不被 Tomcat 挡、超长拒绝、空 body 的文案。
它会起两个上下文（一个配好模型、一个模型目录为空），端口 18090 / 18091，
临时 SQLite 库，跑完自动关。**不需要 `DEEPSEEK_API_KEY`** —— 一次都不调 LLM。

---

## 前端验证程序

不需要 `npm install`，node 直接跑：

```bash
"/c/Program Files/nodejs/node" src/test/js/AsrPageCheck.js
```

| 程序 | 验什么 |
|---|---|
| `AsrPageCheck` | 语音答题这条链：`api()` 的裸二进制分支（以及 JSON / FormData 两条老路没被弄坏）、`asr.js` 的状态机、`asrUseTyping`/`asrReset` **不会**白发一次 `/api/asr/transcribe`、`interview.html` 的模板事实（语音模式下是只读块不是 textarea） |

它自己搭浏览器替身（`localStorage` / `document` / `fetch` / `MediaRecorder` /
`AudioContext` / `navigator`），然后照页面里的方式把 `app.js` 和 `asr.js` 载进来。

> ⚠️ 搭替身时踩过的坑：node 22 自带只读的全局 `navigator`，直接
> `globalThis.navigator = x` 是**静默失败**的（非严格模式不报错、值也不变）。
> 要换掉它必须用 `Object.defineProperty`。

---

## 不在仓库里的

早期几期（面试主链路、复盘页、简历、统计）的验证程序还在 `%TEMP%` 下没搬进来，
比如 `ApiCheck.java`（全链路端到端）、`PageCheck.js`（六个页面 + 图表沙箱）、
`ExportCheck.js`、`DaoCheck.java`、`StatsCheck.java`。

`PageCheck.js` 是本站最重的一份（177 条断言），它**会读仓库里的源码**跑，
所以改了 `app.js` / `charts.js` / 页面模板之后仍然应该跑一遍它做跨页回归：

```bash
"/c/Program Files/nodejs/node" /c/Users/huangjinqing001/AppData/Local/Temp/PageCheck.js
```