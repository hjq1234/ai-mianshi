/*
 * AudioWorklet 处理器：把麦克风的 Float32 分片丢回主线程。
 *
 * 为什么不能继续用 MediaRecorder：它吐的是**压缩容器的碎片**（webm/opus），
 * 只有第一片带容器头，单独丢给 decodeAudioData 解不开。
 * 所以流式这条路必须直接拿裸 PCM —— 这是 AudioWorklet 唯一的存在理由。
 *
 * 这个文件必须能被浏览器单独 fetch 到（addModule 的参数是个 URL），
 * 所以不能内联进 asr-stream.js，也不能被任何打包步骤合并。
 *
 * 注意 AudioWorkletGlobalScope 里**没有 window / document / Vue**，只有
 * console 之类最基本的几个。这里只能做最纯粹的攒帧 + postMessage。
 */

class AsrPcmProcessor extends AudioWorkletProcessor {

  constructor(options) {
    super();
    // 1600 帧 @16k = 100ms。太小会把 HTTP 请求数撑上去（每片都要带一份几百字节的头），
    // 太大则出字一顿一顿的。100ms 是这两头中间最舒服的位置
    const opt = (options && options.processorOptions) || {};
    this.frameSize = opt.frameSize || 1600;
    this.buf = new Float32Array(this.frameSize);
    this.n = 0;
  }

  process(inputs) {
    const input = inputs[0];
    const ch = input && input[0];
    // 麦克风还没接上、或者这一帧没人拉数据时 ch 是空的。
    // 返回 true 表示「这个处理器还活着」—— 返回 false 会被浏览器回收掉，声音就永远断了
    if (!ch) {
      return true;
    }
    for (let i = 0; i < ch.length; i++) {
      this.buf[this.n++] = ch[i];
      if (this.n === this.frameSize) {
        // slice() 是必须的：不复制的话 postMessage 传的是同一个 buffer，
        // 下一帧会把它写花，主线程拿到的是「正在被改的那块内存」
        this.port.postMessage(this.buf.slice(0));
        this.n = 0;
      }
    }
    return true;
  }
}

registerProcessor('asr-pcm', AsrPcmProcessor);