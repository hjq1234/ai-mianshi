/*
 * 流式语音识别：AudioWorklet 实时取 PCM → 每 100ms POST 一片 → 边说边出字。
 *
 * 和 asr.js 平级、互不引用。两条路的采集方式根本不同（那边 MediaRecorder 录整段，
 * 这边 AudioWorklet 实时拿裸 PCM），硬合成一个文件只会让两边都难读。
 * 页面（interview.html）负责按 asrMode 分发，见那边的 micStart / micStop。
 *
 * 三个容易踩的地方：
 *  1. **不能直接写 textarea**。partial 一直在被改写（「我要用 redis」→「我要用 Redis 做缓存」），
 *     写进框里会把用户打的草稿反复搅乱，光标位置也没了。所以 partial 只进 asrPartial
 *     （麦克风上方那行灰字），只有 finalText 才追加进框
 *  2. **分片必须按顺序发**。音频是时序数据，两片乱序 = 音频被搅乱，
 *     转出来是一段流利但完全不对的中文，**不会报错**。所以是「等上一个响应回来才发下一个」
 *  3. **AudioContext 要 {sampleRate: 16000}**。浏览器会替我们把麦克风重采样到 16k，
 *     asr.js 那套 OfflineAudioContext 手动重采样在这条路上完全不需要
 */

/** 两个模式的显示名。key 和 asrMode 的取值一一对应 */
const ASR_STREAM_MODES = { offline: '离线', stream: '流式' };
const ASR_MODE_KEY = 'asrMode';

/** 挂进页面的 data。都由 interview.html 展开一次 */
function asrStreamData() {
  return {
    asrStreamAvailable: false,  // /status 说的，决定「流式」这个选项能不能选
    asrStreamReason: '',        // 不可用时的一句人话（进 title，不然用户不知道为什么是灰的）
    asrMode: 'stream',          // offline | stream。默认流式
    asrPartial: ''              // 正在说的这半句，一直在变，**不进 textarea**
  };
}

const ASR_STREAM_METHODS = {

  /** 读回上次选的模式。localStorage 挂了（隐私模式）就用默认值，不能让页面起不来 */
  asrModeInit() {
    try {
      const saved = localStorage.getItem(ASR_MODE_KEY);
      if (saved === 'offline' || saved === 'stream') this.asrMode = saved;
    } catch (e) { /* 隐私模式下 localStorage 会抛，忽略 */ }
  },

  /** 切模式。录音中要先收掉这次录音 —— 不然麦克风还开着，模式已经变了 */
  asrModeSet(mode) {
    if (mode !== 'offline' && mode !== 'stream') return;
    if (this.asrState === 'recording') this.micStop();
    this.asrMode = mode;
    try {
      localStorage.setItem(ASR_MODE_KEY, mode);
    } catch (e) { /* 同上 */ }
    this.asrError = '';
  },

  /**
   * 当前模式下麦克风该不该显示。
   *
   * 流式不可用（没下那个模型）时那个选项是灰的，但 asrMode 可能还存着 'stream' ——
   * 所以这里要判 asrStreamAvailable，不能只看 asrMode
   */
  micAvailable() {
    return this.asrMode === 'stream' ? this.asrStreamAvailable : this.asrAvailable;
  },

  async asrStreamStart() {
    this.asrCancel();
    this.asrError = '';
    this.asrPartial = '';
    this.asrState = 'idle';
    // 和 asrStart 一样**不清 answer**：框里可能是打了一半的草稿，重录一次不该把它抹掉

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia
        || typeof AudioWorkletNode === 'undefined') {
      this.asrError = '这个浏览器不支持流式录音（要 AudioWorklet），切到「离线」就能用';
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

    try {
      const s = await api('/api/asr/stream/start', { method: 'POST' });
      this._asrSessionId = s.sessionId;
    } catch (e) {
      this.asrReleaseMic();
      this.asrError = e.message || '开不了语音会话，重试一次';
      return;
    }

    try {
      // ★ {sampleRate: 16000}：浏览器替我们把麦克风重采样到 16k。
      // asr.js 那条路要手动跑一遍 OfflineAudioContext，这里不用 —— 这是流式最省事的一步
      const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: ASR_TARGET_RATE });
      this._asrCtx = ctx;
      await ctx.audioWorklet.addModule('js/asr-pcm-worklet.js');

      // ctx.sampleRate 拿不到 16000 时（老浏览器会忽略构造参数里的 sampleRate）**不静默继续**：
      // 采样率不对不会报错，只会转出一段看着像话其实全错的中文。
      // 和服务端那句「采样率只支持 16000」是同一个判断，只不过这里只能降级不能拒
      if (ctx.sampleRate !== ASR_TARGET_RATE) {
        throw new Error('浏览器不给 16000 Hz 的音频上下文（拿到 '
          + ctx.sampleRate + '），切到「离线」就能用');
      }

      const node = new AudioWorkletNode(ctx, 'asr-pcm');
      node.port.onmessage = (e) => this.asrStreamFeed(e.data);
      const src = ctx.createMediaStreamSource(stream);
      src.connect(node);

      // 空 gain 接到 destination：AudioWorkletNode 得挂在图上才会被拉数据，
      // 直接连 destination 就是把麦克风原声放出来（啸叫）。
      // gain 0 让它照样被拉，但一个字节都不出声
      const mute = ctx.createGain();
      mute.gain.value = 0;
      node.connect(mute);
      mute.connect(ctx.destination);

      // 电平条复用 asr.js 那套：形状装成一样的，asrRms 和 ticker 就不用改
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      src.connect(analyser);
      this._asrAudio = { ctx, analyser, buf: new Uint8Array(analyser.fftSize) };

      this._asrQueue = [];
      this._asrPumping = false;
      this._asrStartedAt = Date.now();
      this.asrSeconds = 0;
      this.asrState = 'recording';
      this.asrStartTicker();
    } catch (e) {
      await this.asrStreamStop(true);
      this.asrError = e.message || '流式录音启动失败，切到「离线」还能用';
      this.asrState = 'idle';
    }
  },

  /** worklet 每 100ms 送一片 Float32 来 */
  asrStreamFeed(f32) {
    if (this.asrState !== 'recording') return;
    // Float32 → Int16：一半的带宽，而且和服务端 PcmCodec.toFloats 是同一套约定
    // （和离线那条路发的字节完全一样），服务端不用为流式多写一份解码
    const pcm = new Int16Array(f32.length);
    for (let i = 0; i < f32.length; i++) {
      const v = Math.max(-1, Math.min(1, f32[i]));
      pcm[i] = v < 0 ? v * 0x8000 : v * 0x7FFF;
    }
    this._asrQueue.push(pcm);
    this.asrStreamPump();
  },

  /**
   * 一片一片按顺序发。**不并发**：音频是时序数据，两片乱序到达，
   * 转出来是一段流利但完全不对的中文，而且不会报错。
   */
  async asrStreamPump() {
    if (this._asrPumping) return;
    this._asrPumping = true;
    try {
      while (this._asrQueue.length && this.asrState === 'recording') {
        const pcm = this._asrQueue.shift();
        const r = await api('/api/asr/stream/chunk?sessionId=' + this._asrSessionId, {
          method: 'POST', body: pcm, contentType: 'application/octet-stream'
        });
        // 定稿的先追加，再更新灰字 —— 顺序反了的话，句子进框之后灰字还挂着上一句，
        // 看着像重复识别了一遍
        if (r.finalText) this.asrAppendText(r.finalText);
        this.asrPartial = r.partial || '';
      }
    } catch (e) {
      this.asrError = e.message || '流式识别中断了，切到「离线」可以接着说';
      await this.asrStreamStop(true);
      this.asrState = 'idle';
    } finally {
      this._asrPumping = false;
    }
  },

  /**
   * 收掉流式这次录音，并把最后没定稿的那半句拿出来。
   *
   * @param discard true = 这次录音不要了（用户提交了 / 切模式了），最后那半句丢掉
   */
  async asrStreamStop(discard) {
    this.asrStopTicker();
    this.asrLevel = 0;
    this.asrReleaseMic();
    const sid = this._asrSessionId;
    this._asrSessionId = null;
    this._asrQueue = [];
    this.asrPartial = '';
    if (!sid) return;

    try {
      const r = await api('/api/asr/stream/stop?sessionId=' + sid, { method: 'POST' });
      // discard 时**仍然发 stop**，只是不追加文本：不像离线那边能靠不调 transcribe 省掉一次
      // 整段解码的重活，流式这边 stop 本来就必须发给服务端 —— 会话在服务端手里，
      // 不发它就得等 2 分钟过期回收。所以这里没有单独的 cancel 路径
      if (!discard && r.finalText) this.asrAppendText(r.finalText);
    } catch (e) {
      if (!discard) this.asrError = e.message || '收尾失败，最后那几个字可能没进去';
    }
  },

  /** 定稿的文字进答题框。追加不覆盖 —— 和 asr.js 里 asrTranscribe 是同一套语义 */
  asrAppendText(text) {
    const t = (text || '').trim();
    if (!t) return;
    const typed = (this.answer || '').trim();
    this.answer = typed ? typed + ' ' + t : t;
    this.asrState = 'done';
  }
};