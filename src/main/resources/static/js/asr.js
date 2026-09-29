/*
 * 语音答题：录音 → 页内重采样成 16k 单声道 PCM16 → 上传 → 只读转写块。
 *
 * 为什么在浏览器里重采样：OfflineAudioContext 就是浏览器自带的采样率转换器，
 * 用它降到 16k 之后，服务端完全不用碰音频格式（不引 FFmpeg、不引音频库），
 * 收到的就是 PCM16 字节。
 *
 * 状态机：idle → recording → transcribing → done →（重录回 idle）
 *                                     ↘（出错回 idle + asrError）
 */

const ASR_TARGET_RATE = 16000;

/** 挂进页面的 data。都由 interview.html 展开一次 */
function asrData() {
  return {
    asrAvailable: false,   // /status 说的，决定麦克风按钮显不显示
    asrMaxSeconds: 120,    // 也来自 /status，避免前端后端各写一个上限
    asrMode: 'voice',      // voice | typing
    asrState: 'idle',      // idle | recording | transcribing | done
    asrText: '',           // 转写结果，只读
    asrError: '',          // 出错文案，每条都要给出路
    asrSeconds: 0,
    asrLevel: 0            // 0..1，画电平条
  };
}

const ASR_METHODS = {

  /** 页面加载时调一次。拿不到就当没有语音 —— 打字的路径必须不受影响 */
  async asrInit() {
    try {
      const s = await api('/api/asr/status');
      this.asrAvailable = !!s.available;
      if (s.maxSeconds) this.asrMaxSeconds = s.maxSeconds;
    } catch (e) {
      this.asrAvailable = false;
    }
    this.asrMode = this.asrAvailable ? 'voice' : 'typing';
  },

  async asrStart() {
    // 先把上一次的计时器和 recorder 收干净 —— 重录时可能还留着一个没停的 recorder，
    // 它一 stop 就会触发 onstop，和这次新录的撞在一起
    this.asrCancel();
    this.asrError = '';
    this.asrText = '';
    this.asrState = 'idle';

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia
        || typeof MediaRecorder === 'undefined') {
      this.asrError = '这个浏览器不支持录音，用打字回答就行';
      return;
    }

    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      this.asrError = '没拿到麦克风权限。浏览器地址栏左边可以改回来，或者直接打字回答';
      return;
    }

    this._asrStream = stream;
    this._asrChunks = [];

    // 不指定 mimeType：各浏览器支持的容器不一样（Chrome 是 webm/opus，Safari 是 mp4/aac），
    // 而这里录完立刻 decodeAudioData 成 PCM 了，容器是什么根本不影响后面 —— 让浏览器自己挑最稳
    const rec = new MediaRecorder(stream);
    rec.ondataavailable = (e) => { if (e.data && e.data.size) this._asrChunks.push(e.data); };
    rec.onstop = () => {
      // 在 onstop 里才放麦克风：stop() 之后还有最后一小块数据要冲出来，
      // 提前关轨道会把它截掉
      this.asrReleaseMic();
      this.asrTranscribe(new Blob(this._asrChunks));
    };
    rec.onerror = () => {
      this.asrReleaseMic();
      this.asrState = 'idle';
      this.asrError = '录音出错了，重录一次试试';
    };
    this._asrRecorder = rec;

    // 电平条：没有它，用户不知道麦克风到底有没有收到声音，
    // 录完发现是空的才重来一遍
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const src = ctx.createMediaStreamSource(stream);
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 1024;
    src.connect(analyser);
    this._asrAudio = { ctx, analyser, buf: new Uint8Array(analyser.fftSize) };

    this._asrStartedAt = Date.now();
    this.asrSeconds = 0;
    this.asrState = 'recording';
    rec.start();

    // 100ms 一跳：电平条要顺，秒数要准（按 Date.now 算，不靠累加，累加会被 setInterval 的漂移带偏）
    this._asrTimer = setInterval(() => {
      this.asrSeconds = Math.floor((Date.now() - this._asrStartedAt) / 1000);
      this.asrLevel = asrRms(this._asrAudio.analyser, this._asrAudio.buf);
      // 到上限自动停。等用户自己发现「已经说了两分钟」不如替她停掉 ——
      // 停了还能转写；超了服务端会直接拒掉，那段话就白说了
      if (this.asrSeconds >= this.asrMaxSeconds) this.asrStop();
    }, 100);
  },

  asrStop() {
    if (this.asrState !== 'recording') return;
    clearInterval(this._asrTimer);
    this._asrTimer = null;
    this.asrLevel = 0;
    this.asrState = 'transcribing';
    // 后面交给 onstop（放麦克风 → 转写）
    if (this._asrRecorder && this._asrRecorder.state !== 'inactive') {
      this._asrRecorder.stop();
    }
  },

  /** 关掉麦克风和 AudioContext。不关的话标签页上一直亮着录音红点 */
  asrReleaseMic() {
    if (this._asrStream) {
      this._asrStream.getTracks().forEach(t => t.stop());
      this._asrStream = null;
    }
    if (this._asrAudio) {
      this._asrAudio.ctx.close();
      this._asrAudio = null;
    }
  },

  /**
   * 丢掉当前录音，**不做转写**。
   *
   * 和 asrStop() 的区别是这条路不转写：asrStop() 之后 recorder.stop() 会触发 onstop，
   * 而 onstop 里就是「放麦克风 → 上传转写」。所以「改用打字」「提交完清状态」这些
   * 场景**不能**走 asrStop()，否则用户放弃了这次录音，却还是白发一次转写请求。
   * 关键动作是先把 onstop 摘掉再 stop()——摘了就没人接这个事件了。
   */
  asrCancel() {
    if (this._asrTimer) {
      clearInterval(this._asrTimer);
      this._asrTimer = null;
    }
    if (this._asrRecorder && this._asrRecorder.state !== 'inactive') {
      this._asrRecorder.onstop = null;
      this._asrRecorder.stop();
    }
    this._asrRecorder = null;
    this.asrReleaseMic();
    this.asrLevel = 0;
  },

  async asrTranscribe(blob) {
    this.asrState = 'transcribing';
    try {
      if (!blob || !blob.size) throw new Error('没录到声音，重录一次试试');
      const pcm = await asrToPcm16k(blob);
      if (!pcm.length) throw new Error('没听到内容。可能是麦克风没收到声音，重录一次试试');

      const data = await api('/api/asr/transcribe?sampleRate=' + ASR_TARGET_RATE, {
        method: 'POST',
        body: pcm,
        contentType: 'application/octet-stream'
      });
      const text = ((data && data.text) || '').trim();
      if (!text) {
        this.asrState = 'idle';
        this.asrError = '没听到内容。可能是麦克风没收到声音，重录一次试试';
        return;
      }
      this.asrText = text;
      this.asrState = 'done';
    } catch (e) {
      this.asrState = 'idle';
      this.asrError = e.message || '转写失败，重录一次试试';
    }
  },

  /** 改成打字。录了一半 / 转写失败时都能走这条，不能让人卡在一个坏掉的按钮上 */
  asrUseTyping() {
    // 用 asrCancel 不是 asrStop：既然改用打字了，这次录音就该丢掉，不该再转写一遍
    this.asrCancel();
    this.asrState = 'idle';
    this.asrMode = 'typing';
    this.asrError = '';
  },

  asrUseVoice() {
    this.asrMode = 'voice';
    this.asrError = '';
  },

  /** 提交后清干净，否则下一题还挂着上一题的转写 */
  asrReset() {
    this.asrCancel();
    this.asrState = 'idle';
    this.asrText = '';
    this.asrError = '';
    this.asrSeconds = 0;
  },

  /**
   * 该提交哪个字符串。语音模式下是转写文本，打字模式下是 textarea。
   * 两条路走的是同一个 /answer 接口 —— 后端不知道也不需要知道答案是怎么来的。
   */
  asrSubmitText() {
    return this.asrMode === 'voice' && this.asrState === 'done'
      ? (this.asrText || '').trim()
      : (this.answer || '').trim();
  }
};

/** 时域数据的 RMS，画电平条用 */
function asrRms(analyser, buf) {
  analyser.getByteTimeDomainData(buf);
  let sum = 0;
  for (let i = 0; i < buf.length; i++) {
    const v = (buf[i] - 128) / 128;
    sum += v * v;
  }
  return Math.min(1, Math.sqrt(sum / buf.length) * 3); // ×3 只是让小声说话也能看见条动
}

/** 录出来的容器 → 16k 单声道 Float32 */
async function asrToPcm16k(blob) {
  const decoded = await asrDecode(blob);

  // OfflineAudioContext 一次干两件事：构造参数 1 是「混成单声道」，
  // 第三个参数是目标采样率。它用的是浏览器自己的重采样器，不用手写插值
  const frames = Math.max(1, Math.ceil(decoded.duration * ASR_TARGET_RATE));
  const off = new OfflineAudioContext(1, frames, ASR_TARGET_RATE);
  const src = off.createBufferSource();
  src.buffer = decoded;
  src.connect(off.destination);
  src.start();
  const rendered = await off.startRendering();
  const floats = rendered.getChannelData(0);

  // Float32 → Int16。Int16Array 用的是本机字节序，x86/ARM 都是小端，
  // 正好是服务端要的 PCM16LE —— 这个假设由 src/test/java/.../checks/PcmCheck.java
  // 在服务端那一侧盯着
  const out = new Int16Array(floats.length);
  for (let i = 0; i < floats.length; i++) {
    const s = Math.max(-1, Math.min(1, floats[i]));
    out[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
  }
  return out;
}

/** decodeAudioData 包成 async。现代浏览器（有 MediaRecorder 的那些）都支持 Promise 形式 */
async function asrDecode(blob) {
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  try {
    return await ctx.decodeAudioData(await blob.arrayBuffer());
  } catch (e) {
    throw new Error('这段录音解不开，重录一次试试');
  } finally {
    ctx.close();
  }
}