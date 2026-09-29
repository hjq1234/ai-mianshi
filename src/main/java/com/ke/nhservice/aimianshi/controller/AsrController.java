package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.AsrStatusVO;
import com.ke.nhservice.aimianshi.common.dto.AsrStreamVO;
import com.ke.nhservice.aimianshi.common.dto.AsrTextVO;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrClient;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrProperties;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrStatus;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrStreamClient;
import com.ke.nhservice.aimianshi.wrapper.asr.PcmCodec;
import com.ke.nhservice.aimianshi.wrapper.asr.StreamChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asr")
public class AsrController {

    private static final Logger log = LoggerFactory.getLogger(AsrController.class);

    /** 模型要的采样率。前端在浏览器里已经重采样成这个值了 */
    private static final int SAMPLE_RATE = 16000;

    private final AsrClient asrClient;
    private final AsrStreamClient asrStreamClient;
    private final AsrProperties props;

    public AsrController(AsrClient asrClient, AsrStreamClient asrStreamClient, AsrProperties props) {
        this.asrClient = asrClient;
        this.asrStreamClient = asrStreamClient;
        this.props = props;
    }

    /** 前端靠这个决定要不要显示麦克风按钮、录音上限多少秒、以及「流式」那个选项给不给 */
    @GetMapping("/status")
    public ApiResponse<AsrStatusVO> status() {
        // 两条路各报各的。**不合成一个 available**：模型是两个不同的目录，
        // 只下了一个是很正常的状态，合成一个布尔就说不清到底是缺哪个
        return ApiResponse.ok(AsrStatusVO.of(asrClient.status(), asrStreamClient.status()));
    }

    /**
     * body 是裸的 PCM16 小端字节（Content-Type: application/octet-stream），不是 JSON。
     * 浏览器那边已经把 44.1k/48k 的录音重采样成 16k 单声道了，服务端不碰音频格式，
     * 所以不引 FFmpeg、不引任何音频库。
     */
    @PostMapping("/transcribe")
    public ApiResponse<AsrTextVO> transcribe(@RequestBody(required = false) byte[] body,
                                             @RequestParam(defaultValue = "16000") int sampleRate) {
        // 采样率错了不会报错，只会转出一段看着像话、其实全错的文本。
        // 这种静默失败比一个 400 难查得多，所以宁可拒掉也不猜
        if (sampleRate != SAMPLE_RATE) {
            throw new BizException("采样率只支持 " + SAMPLE_RATE + "，收到 " + sampleRate);
        }
        // required = false + 这里的判断，两半缺一不可：默认的 required = true 会让
        // Spring 在进方法之前就抛 HttpMessageNotReadableException，用户看到的是
        // 「服务器内部错误：Required request body is missing: ...」这种框架英文 + 一个 500
        if (body == null || body.length == 0) {
            throw new BizException("没有收到音频数据");
        }

        // 先问 status：模型没配 / native 没加载上时，要回一句能看懂的话，
        // 而不是让 native 侧去啃一个不存在的文件
        AsrStatus st = asrClient.status();
        if (!st.available()) {
            throw new BizException("语音识别不可用：" + st.reason());
        }

        float[] samples = PcmCodec.toFloats(body);
        if (samples.length > (long) props.getMaxSeconds() * SAMPLE_RATE) {
            throw new BizException("音频太长（" + (samples.length / SAMPLE_RATE)
                    + " 秒），上限 " + props.getMaxSeconds() + " 秒");
        }

        long startedAt = System.currentTimeMillis();
        String text = asrClient.transcribe(samples, sampleRate);

        // 打耗时和字数，**不打转写出来的文本** —— 回答是候选人的隐私内容
        log.info("ASR 转写 | {} ms | 音频 {} 秒 | {} 字节 | 结果 {} 字符",
                System.currentTimeMillis() - startedAt, samples.length / SAMPLE_RATE,
                body.length, text.length());
        return ApiResponse.ok(new AsrTextVO(text));
    }

    /* ─────────────────── 流式：start → chunk × N → stop ───────────────────
     *
     * 为什么不一次请求说完整段：那样就又变成离线那条路了。
     * 这三步是「边说边出字」的最小形状，前端的麦克风 AudioWorklet 每 100ms 走一次 chunk。
     */

    /** 开一次流式会话。前端拿到 sessionId 后每 100ms 往 /chunk 送一片 */
    @PostMapping("/stream/start")
    public ApiResponse<AsrStreamVO> streamStart() {
        AsrStatus st = asrStreamClient.status();
        if (!st.available()) {
            throw new BizException("流式语音识别不可用：" + st.reason());
        }
        String id = asrStreamClient.start();
        if (id == null) {
            // 和上面那几句一样，「怎么跟用户说」是这一层的事，wrapper 只负责返 null
            throw new BizException("同时进行的语音会话太多了，等一下再试（上一次录音可能没正常结束）");
        }
        return ApiResponse.ok(AsrStreamVO.started(id));
    }

    /**
     * 一片音频。body 和 /transcribe 一样是裸的 PCM16 小端。
     *
     * 单片长度**不设上限**（只校验采样率）：这里没有「整段多长」的概念，
     * 会话总长由前端的录音计时器管。真要防的是有人往这里灌超大 body，
     * 那是 Tomcat 的 max-swallow-size 该管的事，不是业务判断。
     */
    @PostMapping("/stream/chunk")
    public ApiResponse<AsrStreamVO> streamChunk(@RequestBody(required = false) byte[] body,
                                                @RequestParam String sessionId,
                                                @RequestParam(defaultValue = "16000") int sampleRate) {
        if (sampleRate != SAMPLE_RATE) {
            throw new BizException("采样率只支持 " + SAMPLE_RATE + "，收到 " + sampleRate);
        }
        if (body == null || body.length == 0) {
            throw new BizException("没有收到音频数据");
        }
        if (!asrStreamClient.has(sessionId)) {
            // 过期和不存在都走这句：分开说对用户没意义，她要做的都是「重录一次」
            throw new BizException("这次录音的会话已经过期了，重新点一次麦克风");
        }
        StreamChunk c = asrStreamClient.chunk(sessionId, PcmCodec.toFloats(body), sampleRate);
        return ApiResponse.ok(AsrStreamVO.chunk(c.partial(), c.finalText()));
    }

    /** 结束会话。返回的 finalText 是最后没定稿的那半句，前端要追加进答题框 */
    @PostMapping("/stream/stop")
    public ApiResponse<AsrStreamVO> streamStop(@RequestParam String sessionId) {
        if (!asrStreamClient.has(sessionId)) {
            // 不抛异常：会话过期之后前端可能还在收尾，抛出去是白给用户一个红条。
            // 回空文本，前端那边是「什么都没追加」，和正常结束看起来一样
            return ApiResponse.ok(AsrStreamVO.finished(""));
        }
        long startedAt = System.currentTimeMillis();
        String text = asrStreamClient.stop(sessionId);
        // 只打耗时和字数，**不打文本本身** —— 回答是候选人的隐私内容（和 /transcribe 一致）
        log.info("ASR 流式结束 | {} ms | 结果 {} 字符", System.currentTimeMillis() - startedAt, text.length());
        return ApiResponse.ok(AsrStreamVO.finished(text));
    }
}