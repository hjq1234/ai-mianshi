package com.ke.nhservice.aimianshi.wrapper.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsrConfig {

    private static final Logger log = LoggerFactory.getLogger(AsrConfig.class);

    @Bean
    public AsrClient asrClient(AsrProperties props) {
        AsrClient client = new SherpaAsrClient(props);

        // 启动时打一次可用性。不打的话「答题区没有麦克风按钮」只能靠猜：
        // 前端 in asrInit 是**故意静默**降级成打字的（拿不到就当没有语音，打字那条路不能受影响），
        // 所以后端这行日志是唯一说得出原因的地方（模型目录没配 / 文件不存在 / dll 没加载上）。
        //
        // status() 会触发 native 库加载（VersionInfo），但不加载模型 —— 那 228 MB 仍然
        // 留到第一次真正转写。加载失败也只是返回 available:false，不会让应用起不来。
        AsrStatus status = client.status();
        if (status.available()) {
            log.info("语音识别已启用 | native={} | 模型={} | 单次上限={} 秒",
                    status.nativeVersion(), props.modelPath(), status.maxSeconds());
        } else {
            // 不再补一句「环境变量 APP_ASR_MODEL_DIR」：reason 里该说的都说了，
            // 而模型文件不存在、dll 没加载上这两种情况补那句反而误导
            log.warn("语音识别未启用，答题区只会显示打字输入 | {}", status.reason());
        }
        return client;
    }

    @Bean
    public AsrStreamClient asrStreamClient(AsrProperties props) {
        AsrStreamClient client = new SherpaStreamAsrClient(props);

        // 两条路各自报各自的，**不合成一行**：只下了离线模型是很正常的状态，
        // 合成一行就说不清缺的是哪个
        AsrStatus status = client.status();
        if (status.available()) {
            log.info("流式语音识别已启用 | 模型目录={}", props.getStream().getModelDir());
        } else {
            // 这里用 info 不是 warn，和上面那个不一样：上面不可用意味着「语音整个没了」，
            // 这里不可用只是「少了一种录音方式」，答题照常
            log.info("流式语音识别未启用，答题区的语音只有「离线」那一个选项 | {}", status.reason());
        }
        return client;
    }
}