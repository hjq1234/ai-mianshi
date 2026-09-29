package com.ke.nhservice.aimianshi.wrapper.asr;

/**
 * 浏览器录出来的裸 PCM16 **小端** 字节 → sherpa-onnx 要的 [-1,1] float。
 *
 * 单独成类而不是塞进 controller，是因为字节序写错**不会抛异常**，只会转出一段
 * 看着像话、其实全错的文本。这种错必须能单独验（见 %TEMP%\PcmCheck.java）。
 */
public final class PcmCodec {

    private PcmCodec() {
    }

    public static float[] toFloats(byte[] pcm) {
        // 奇数长度说明数据被截断了。丢掉最后那个落单的字节，
        // 而不是让它和前面的错位成一对——错位会让整段音频全乱
        int count = pcm.length / 2;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            int lo = pcm[i * 2] & 0xFF;      // 低位：无符号
            int hi = pcm[i * 2 + 1];         // 高位：保留符号，左移 8 位就是 16 位有符号数
            short sample = (short) ((hi << 8) | lo);
            out[i] = sample / 32768.0f;
        }
        return out;
    }
}