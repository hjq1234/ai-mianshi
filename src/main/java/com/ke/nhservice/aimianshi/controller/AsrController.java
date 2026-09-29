package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.AsrStatusVO;
import com.ke.nhservice.aimianshi.common.dto.AsrTextVO;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrClient;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrProperties;
import com.ke.nhservice.aimianshi.wrapper.asr.AsrStatus;
import com.ke.nhservice.aimianshi.wrapper.asr.PcmCodec;
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
    private final AsrProperties props;

    public AsrController(AsrClient asrClient, AsrProperties props) {
        this.asrClient = asrClient;
        this.props = props;
    }

    /** 前端靠这个决定要不要显示麦克风按钮，以及录音上限是多少秒 */
    @GetMapping("/status")
    public ApiResponse<AsrStatusVO> status() {
        return ApiResponse.ok(AsrStatusVO.of(asrClient.status()));
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
}