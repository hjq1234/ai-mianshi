/*
 * 流式语音前端（asr-stream.js + interview.html 的分发）的检查程序。
 *
 * 为什么和 AsrPageCheck.js 分开：两条路的浏览器替身完全不搭 ——
 * 那边装 MediaRecorder + decodeAudioData，这边装 AudioWorkletNode + audioWorklet.addModule。
 * 合成一份的话每个用例都要先声明「我现在测哪条」，读起来比两份文件还累。
 *
 * 跑法（仓库根目录）：
 *   node src/test/js/AsrStreamPageCheck.js
 *
 * 它盯住四件事，全都是「写错了不报错、只是行为不对」的那种：
 *   1. ★ partial **不进**答题框，只有 finalText 追加 —— partial 一直在被改写，
 *      写进框里会把用户打的草稿反复搅乱（而且看不见是谁干的）
 *   2. ★ 分片**串行**发 —— 并发的话音频乱序，转出来是一段流利但完全不对的中文，**不报错**
 *   3. ★ 提交时 discard：最后那半句不追加，但 stop **照发**（不发的话服务端会话要等
 *      2 分钟过期才回收，而 maxSessions 只有 4 个）
 *   4. ★ micAvailable 只看**当前模式**对应的那个 available
 */

const fs = require('fs');
const path = require('path');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const read = f => fs.readFileSync(path.join(STATIC, f), 'utf8');

let pass = 0, fail = 0;
function check(name, ok, detail = '') {
  if (ok) pass++; else fail++;
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : ''));
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ────────────────────────── 浏览器环境替身 ──────────────────────────

const store = {};
globalThis.localStorage = {
  getItem: k => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v); },
  removeItem: k => { delete store[k]; }
};

globalThis.location = { search: '', replace: () => {} };
globalThis.document = { createElement: () => ({ click() {}, remove() {} }), body: { appendChild() {} } };
globalThis.window = {};
globalThis.Blob = class { constructor(parts) { this.parts = parts || []; } };

// 请求记账。router 返回 { data, delay, throws, status }
let calls = [], inflight = 0, maxInflight = 0;
let router = () => ({});
globalThis.fetch = async (url, opts = {}) => {
  calls.push({ url, opts });
  const r = router(url, opts) || {};
  // 记账包住整段（含 delay）：这条就是「有没有两片同时在飞」的探针
  inflight++;
  if (inflight > maxInflight) maxInflight = inflight;
  try {
    if (r.delay) await sleep(r.delay);
    if (r.throws) throw new Error(r.throws);
    return {
      status: r.status || 200,
      json: async () => ({
        // message 要放行：api() 在 code != 0 时抛的就是它，写死 'ok' 的话
        // 「业务错误原样透出来」那条永远测不出东西
        code: r.code === undefined ? 0 : r.code,
        message: r.message || 'ok',
        data: r.data
      })
    };
  } finally {
    inflight--;
  }
};

const chunkCalls = () => calls.filter(c => c.url.includes('/stream/chunk'));
const stopCalls = () => calls.filter(c => c.url.includes('/stream/stop'));

// ────────────────────────── 载入三个 js ──────────────────────────

Object.assign(globalThis, new Function(read('js/app.js') + `
  return { getToken, setToken, clearToken, api, toast, fmtScore };`)());

Object.assign(globalThis, new Function(read('js/asr.js') + `
  return { asrData, ASR_METHODS, asrRms, ASR_TARGET_RATE };`)());

Object.assign(globalThis, new Function(read('js/asr-stream.js') + `
  return { asrStreamData, ASR_STREAM_METHODS, ASR_STREAM_MODES, ASR_MODE_KEY };`)());

globalThis.setToken('t');

/** 拼一个能用的 vm。micStart / micStop 照抄 interview.html 里的分发 */
function makeVm(overrides = {}) {
  const vm = Object.assign({ answer: '' }, asrData(), asrStreamData(), overrides);
  for (const [k, fn] of Object.entries(Object.assign({}, ASR_METHODS, ASR_STREAM_METHODS))) {
    vm[k] = typeof fn === 'function' ? fn.bind(vm) : fn;
  }
  vm.micStart = () => (vm.asrMode === 'stream' ? vm.asrStreamStart() : vm.asrStart());
  vm.micStop = () => (vm.asrMode === 'stream' ? vm.asrStreamStop() : vm.asrStop());
  return vm;
}

/**
 * AudioWorklet 那套替身。
 *
 * sampleRate 可以强行给个假值，用来验「浏览器不给 16000 就报错，不静默继续」那条 ——
 * 静默继续的话采样率不对不会报错，只会转出一段看着像话其实全错的中文。
 */
function installWorkletEnv({ sampleRate = 16000, supportWorklet = true } = {}) {
  const nodes = [];
  // node 自带的 navigator 是只读全局，直接赋值是静默失败，必须 defineProperty
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true, writable: true,
    value: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [{ stop() {} }] }) } }
  });

  if (supportWorklet) {
    globalThis.AudioWorkletNode = function () {
      this.port = { onmessage: null };
      // node.connect(mute) / src.connect(node)：AudioWorkletNode 得挂在图上才会被拉数据。
      // 替身少了这个方法的话，报的是「node.connect is not a function」——
      // 看起来像 asr-stream.js 写错了，其实是替身不全
      this.connect = () => {};
      nodes.push(this);
    };
  } else {
    delete globalThis.AudioWorkletNode;
  }

  globalThis.window.AudioContext = function () {
    // ★ **故意忽略**构造参数里的 sampleRate。真浏览器就是这样：老版本的 Chrome/Safari
    // 会收下 {sampleRate: 16000} 但给你一个硬件原生采样率的上下文，而且**不报错**。
    // 所以这个替身也必须忽略，否则「拿到 48000 就报错」那条永远测不到
    this.sampleRate = sampleRate;
    this.audioWorklet = { addModule: async () => {} };
    this.createMediaStreamSource = () => ({ connect() {} });
    this.createGain = () => ({ gain: { value: 1 }, connect() {} });
    this.destination = {};
    this.createAnalyser = () => ({ fftSize: 0, getByteTimeDomainData() {} });
    this.close = () => {};
  };
  return nodes;
}

/** 开一次流式录音，并把路由装好 */
async function startStream(vm, chunkReplies, extra = {}) {
  let i = 0;
  calls = []; inflight = 0; maxInflight = 0;
  router = (url) => {
    if (url.includes('/stream/start')) return { data: { sessionId: 's1' } };
    if (url.includes('/stream/chunk')) {
      const r = chunkReplies[Math.min(i++, chunkReplies.length - 1)] || {};
      return r;
    }
    if (url.includes('/stream/stop')) return { data: extra.stop || { finalText: '' } };
    return { data: {} };
  };
  await vm.micStart();
  return vm;
}

const frame = (n = 1600) => new Float32Array(n).fill(0.1);

(async () => {

// ══════════════════════ 0. 默认值 + 模式常量 ══════════════════════

{
  const d = asrStreamData();
  check('asrStreamData 默认：默认流式、流式未确认可用、灰字为空',
    d.asrMode === 'stream' && d.asrStreamAvailable === false && d.asrPartial === '',
    JSON.stringify(d));
  check('两个模式的取值就 offline / stream',
    Object.keys(ASR_STREAM_MODES).join(',') === 'offline,stream', JSON.stringify(ASR_STREAM_MODES));
}

// ══════════════════════ 1. ★ partial 不进框，finalText 追加 ══════════════════════

{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  installWorkletEnv();
  await startStream(vm, [
    { data: { partial: '我要用', finalText: '' } },
    { data: { partial: '我要用 Redis', finalText: '' } },
    { data: { partial: '', finalText: '我要用 Redis 做缓存' } }
  ]);

  check('流式录音能起来（替身环境装对了）', vm.asrState === 'recording',
    vm.asrState + (vm.asrError ? ' / ' + vm.asrError : ''));

  vm.asrStreamFeed(frame()); await sleep(30);
  check('★ partial 只进灰字行，**不进答题框**（它下一片就被改写，进框会把草稿搅乱）',
    vm.asrPartial === '我要用' && vm.answer === '',
    JSON.stringify([vm.asrPartial, vm.answer]));

  vm.asrStreamFeed(frame()); await sleep(30);
  check('★ 灰字行跟着改写，框里仍然是空的',
    vm.asrPartial === '我要用 Redis' && vm.answer === '',
    JSON.stringify([vm.asrPartial, vm.answer]));

  vm.asrStreamFeed(frame()); await sleep(30);
  check('★ finalText 到了才追加进框，同时灰字行清空（挂着上一句会像重复识别了一遍）',
    vm.answer === '我要用 Redis 做缓存' && vm.asrPartial === '',
    JSON.stringify([vm.answer, vm.asrPartial]));
  check('定稿之后状态变 done', vm.asrState === 'done', vm.asrState);
}

// 1b. 框里已经有草稿 → 追加不覆盖（和离线那条同一个语义）
{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true, answer: '先打的两句' });
  installWorkletEnv();
  await startStream(vm, [{ data: { finalText: '然后是我说的' } }]);
  vm.asrStreamFeed(frame()); await sleep(30);
  check('★ 框里有草稿时定稿是**追加**，打的字不能被吃掉',
    vm.answer === '先打的两句 然后是我说的', JSON.stringify(vm.answer));
  vm.asrStreamStop(true);
}

// 1c. 空字符串的 finalText 不追加（不然后面多一个空格）
{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true, answer: '草稿' });
  vm.asrAppendText('   ');
  check('反证：finalText 只有空白时不追加（否则每来一片就多一个空格）',
    vm.answer === '草稿', JSON.stringify(vm.answer));
}

// ══════════════════════ 2. ★ 分片串行 ══════════════════════

{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  installWorkletEnv();
  await startStream(vm, [{ data: { partial: 'x', finalText: '' }, delay: 15 }]);

  // 一口气喂 5 片：pump 是异步的，队列里会堆 5 片
  for (let i = 0; i < 5; i++) vm.asrStreamFeed(frame());
  await sleep(220);

  check('★ 5 片全发出去了（不是只发了第一片就卡住）',
    chunkCalls().length === 5, '发了 ' + chunkCalls().length + ' 片');
  check('★ **任何时刻只有一片在飞** —— 并发的话音频乱序，转出来是流利但完全不对的中文，'
    + '而且不报错', maxInflight === 1, '同时最多 ' + maxInflight + ' 个请求在飞');

  const urls = chunkCalls().map(c => c.url);
  check('每片都带上了同一个 sessionId',
    urls.every(u => u.includes('sessionId=s1')), urls[0]);

  const bodies = chunkCalls().map(c => c.opts.body);
  check('★ 每片都是裸 Int16Array + octet-stream（和服务端 PcmCodec 同一套约定）',
    bodies.every(b => b instanceof Int16Array)
    && chunkCalls().every(c => c.opts.headers['Content-Type'] === 'application/octet-stream'),
    bodies[0] && bodies[0].constructor.name);
  check('每片 1600 帧 = 100ms（worklet 的帧长，不是随便凑的）',
    bodies.length > 0 && bodies[0].length === 1600, 'length=' + (bodies[0] || {}).length);
  vm.asrStreamStop(true);
}

// 2b. Float32 超范围要夹到 Int16 里（不夹的话溢出成噪音，听起来像识别不准）
{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  installWorkletEnv();
  await startStream(vm, [{ data: {} }]);
  const loud = new Float32Array(8);
  loud[0] = 5; loud[1] = -5; loud[2] = 0.5;
  vm.asrStreamFeed(loud);
  await sleep(20);
  const pcm = chunkCalls()[0].opts.body;
  check('★ 超过 ±1 的采样被夹住（5 → 32767，-5 → -32768），不是溢出成噪音',
    pcm[0] === 32767 && pcm[1] === -32768 && pcm[2] > 16000,
    Array.from(pcm.slice(0, 3)).join(','));
  vm.asrStreamStop(true);
}

// ══════════════════════ 3. ★ 停止 / 提交时的 discard ══════════════════════

// 3a. 点停止（discard 不传）→ 最后那半句**保留**
{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  installWorkletEnv();
  await startStream(vm, [{ data: { partial: '半句话' } }], { stop: { finalText: '这是最后那半句' } });
  vm.asrStreamFeed(frame()); await sleep(30);

  await vm.micStop();
  check('★ 点停止 → stop 回的最后那半句进框（不收的话用户刚说的就没了）',
    vm.answer === '这是最后那半句', JSON.stringify(vm.answer));
  // 状态是 'done' 不是 'idle'：定稿的字进了框，就是离线那条转写成功的同一个状态
  // （模板里「已转写，可以直接改」靠它）。回 idle 反而会让那句提示不显示
  check('停止后灰字行清空，状态是 done（= 已转写，可以直接改）',
    vm.asrPartial === '' && vm.asrState === 'done', JSON.stringify([vm.asrPartial, vm.asrState]));
  check('停止后麦克风放掉了（标签页上不能一直亮着录音红点）',
    vm._asrStream === null, String(vm._asrStream));
}

// 3b. ★ 提交（discard=true）→ 不追加，但 stop 照发
{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true, answer: '要交上去的答案' });
  installWorkletEnv();
  await startStream(vm, [{ data: { partial: '半句话' } }], { stop: { finalText: '不该进来的' } });
  vm.asrStreamFeed(frame()); await sleep(30);

  await vm.asrStreamStop(true);
  check('★ 提交时 discard → 最后那半句**不**追加（这道题已经交了，多出来的字会落到下一题）',
    vm.answer === '要交上去的答案', JSON.stringify(vm.answer));
  check('★ 但 stop **照发**（不发的话服务端会话要等 2 分钟过期才回收，maxSessions 只有 4 个）',
    stopCalls().length === 1, 'stop 发了 ' + stopCalls().length + ' 次');
  check('★ discard 之后本地会话 id 也清了（不清的话下一次录音会带着旧 id 发片）',
    vm._asrSessionId === null && vm._asrQueue.length === 0,
    JSON.stringify([vm._asrSessionId, vm._asrQueue.length]));
}

// 3c. 反证：不 discard 的话那半句确实会进来 —— 否则 3b 的「没进来」可能只是整条路都坏着
{
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true, answer: '要交上去的答案' });
  installWorkletEnv();
  await startStream(vm, [], { stop: { finalText: '本该进来的' } });
  await vm.asrStreamStop(false);
  check('反证：同一段 stop 响应，不 discard 时确实追加了（所以 3b 的「没追加」是有意义的）',
    vm.answer === '要交上去的答案 本该进来的', JSON.stringify(vm.answer));
}

// ══════════════════════ 4. ★ micAvailable 按模式挑 ══════════════════════

{
  const both = makeVm({ asrAvailable: true, asrStreamAvailable: true, asrMode: 'stream' });
  check('两个都能用时，流式模式下麦克风显示', both.micAvailable() === true, '');

  const onlyOffline = makeVm({ asrAvailable: true, asrStreamAvailable: false, asrMode: 'stream' });
  check('★ 流式没配 + 模式还存着流式 → micAvailable 是 false（不能只看 asrMode）',
    onlyOffline.micAvailable() === false, '');
  onlyOffline.asrMode = 'offline';
  check('★ 同一个 vm 切到离线 → 立刻又能用（回落的必要性就在这）',
    onlyOffline.micAvailable() === true, '');

  const onlyStream = makeVm({ asrAvailable: false, asrStreamAvailable: true, asrMode: 'offline' });
  check('★ 反过来：只有流式时，离线模式下麦克风不显示',
    onlyStream.micAvailable() === false, '');
  onlyStream.asrMode = 'stream';
  check('★ 切到流式 → 又能用', onlyStream.micAvailable() === true, '');
}

// ══════════════════════ 5. asrInit 的回落 + 模式记忆 ══════════════════════

{
  delete store[ASR_MODE_KEY];
  const vm = makeVm();
  vm.asrModeInit();
  check('没存过模式 → 默认流式', vm.asrMode === 'stream', vm.asrMode);

  store[ASR_MODE_KEY] = 'offline';
  const vm2 = makeVm();
  vm2.asrModeInit();
  check('存过 offline → 读回来', vm2.asrMode === 'offline', vm2.asrMode);

  store['asrMode'] = '乱写的值';
  const vm3 = makeVm();
  vm3.asrModeInit();
  check('★ localStorage 里是个野值时不当真（不然会走到一个不存在的模式）',
    vm3.asrMode === 'stream', vm3.asrMode);
}

{
  calls = []; router = () => ({ data: { available: true, streamAvailable: false, streamReason: '流式模型文件不存在：D:/x' } });
  const vm = makeVm({ asrMode: 'stream' });
  await vm.asrInit();
  check('★ 存着流式但流式没配、离线能用 → 落回离线（否则进来就是「按钮不见了」）',
    vm.asrMode === 'offline', vm.asrMode);
  check('两条路的可用性都读回来了，理由也带上了（要进 title）',
    vm.asrAvailable === true && vm.asrStreamAvailable === false
    && vm.asrStreamReason.includes('流式模型文件不存在'),
    JSON.stringify([vm.asrAvailable, vm.asrStreamAvailable, vm.asrStreamReason]));
}

{
  calls = []; router = () => ({ data: { available: false, streamAvailable: false } });
  const vm = makeVm({ asrMode: 'stream' });
  await vm.asrInit();
  check('★ 两个都没有时**保持** stream 不动 —— 改成 offline 是把用户的选择悄悄改了',
    vm.asrMode === 'stream', vm.asrMode);
}

// ══════════════════════ 6. 起不来的时候要给一条出路 ══════════════════════

{
  installWorkletEnv({ supportWorklet: false });
  calls = []; router = () => ({ data: { sessionId: 's1' } });
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  await vm.asrStreamStart();
  check('★ 浏览器没有 AudioWorklet → 文案里指出切到「离线」就能用，状态没卡住',
    vm.asrError.includes('离线') && vm.asrState === 'idle',
    JSON.stringify([vm.asrState, vm.asrError]));
}

{
  // 浏览器忽略 sampleRate 参数：拿到 48000。**不能静默继续** ——
  // 采样率不对不报错，只会转出一段看着像话其实全错的中文
  installWorkletEnv({ sampleRate: 48000 });
  calls = []; router = (url) => url.includes('/stream/start') ? { data: { sessionId: 's1' } } : { data: {} };
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  await vm.asrStreamStart();
  check('★ 浏览器不给 16000 Hz → 报错并给出路，不静默按 48k 录',
    vm.asrState === 'idle' && vm.asrError.includes('48000') && vm.asrError.includes('离线'),
    JSON.stringify([vm.asrState, vm.asrError]));
  check('★ 起不来时麦克风也放掉了（不能报完错还亮着录音红点）',
    vm._asrStream === null, String(vm._asrStream));
  check('起不来时也发了 stop，服务端会话不泄漏',
    stopCalls().length === 1, 'stop 发了 ' + stopCalls().length + ' 次');
}

{
  // 服务端说流式不可用（模型没下）→ 起来之前就该挡住，别去要麦克风权限。
  // 这里要的是**业务错误**（code != 0 时 api() 抛 message），不是网络层 reject ——
  // 后者会被 api() 换成「网络请求失败」，测出来的是另一件事
  installWorkletEnv();
  calls = []; router = (url) => url.includes('/stream/start')
    ? { code: 400, message: '流式语音识别不可用：流式模型文件不存在' }
    : { data: {} };
  const vm = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  await vm.asrStreamStart();
  check('★ /stream/start 回业务错误 → 服务端那句原因原样透出来（不再包一层「网络请求失败」），'
    + '状态回 idle，麦克风放掉',
    vm.asrState === 'idle' && vm.asrError.includes('流式语音识别不可用') && vm._asrStream === null,
    JSON.stringify([vm.asrState, vm.asrError]));

  // 反证：网络层真挂了时给的是另一句 —— 上面那条断的才不是「随便一句错误」
  calls = []; router = () => ({ throws: 'connect ECONNREFUSED' });
  const vm2 = makeVm({ asrAvailable: true, asrStreamAvailable: true });
  await vm2.asrStreamStart();
  check('反证：网络层挂了给的是「网络请求失败」，和上面那句能区分开',
    vm2.asrError.includes('网络请求失败'), vm2.asrError);
}

// ══════════════════════ 7. interview.html 模板 ══════════════════════

{
  const html = read('interview.html');
  const streamSrc = read('js/asr-stream.js');

  check('页面引了 asr-stream.js，且在 asr.js 之后',
    /<script src="js\/asr\.js"><\/script>\s*<script src="js\/asr-stream\.js"><\/script>/.test(html), '');

  check('★ 灰字行插在 textarea 和 answer-bar **之间**（它描述的是「正在说」，'
    + '挂在题目上面或底下都读不出来这层意思）',
    html.indexOf('<textarea') < html.indexOf('class="asr-partial"')
    && html.indexOf('class="asr-partial"') < html.indexOf('class="answer-bar"'), '');

  check('★ 灰字行只在录音中显示，且不看 asrPartial 空不空都得有 v-if（空的时候不留一条空行）',
    /class="asr-partial"\s+v-if="asrState === 'recording' && asrPartial"/.test(html), '');

  check('data() 里展开了 asrStreamData()',
    /data\(\)[\s\S]{0,700}\.\.\.asrStreamData\(\)/.test(html), '');
  check('methods 里展开了 ASR_STREAM_METHODS',
    /methods:\s*\{[\s\S]{0,200}\.\.\.ASR_STREAM_METHODS/.test(html), '');

  check('★ micStart / micStop 的分发在**页面**里，不在 asr-stream.js 里'
    + '（放那边的话两个 js 就互相引用了，少加载一个就是运行时 undefined）',
    /micStart\(\)\s*\{[\s\S]{0,200}asrStreamStart\(\)[\s\S]{0,120}asrStart\(\)/.test(html)
    && !/micStart\s*\(\)\s*\{/.test(streamSrc), '');

  check('★ mounted 里 asrModeInit() 在 asrInit() **之前**'
    + '（反了的话「存着流式但流式没配」那回落判断没机会执行）',
    html.indexOf('this.asrModeInit()') < html.indexOf('this.asrInit()')
    && html.indexOf('this.asrModeInit()') > 0, '');

  check('★ 提交时走 asrStreamStop(true)，不是 asrReset'
    + '（asrReset 只管离线那条，流式会话会挂在服务端等过期）',
    /asrMode === 'stream'[\s\S]{0,120}asrStreamStop\(true\)/.test(html), '');

  check('★ CSS 里有 .asr-partial 和 select.asr-mode',
    /\.asr-partial\s*\{/.test(read('css/app.css'))
    && /select\.asr-mode\s*\{/.test(read('css/app.css')), '');

  check('★ select.asr-mode 显式写了 width: auto —— 全局那条 '
    + '`input, select, textarea { width: 100% }` 会把它撑满，麦克风和提交被挤到下一行',
    /select\.asr-mode\s*\{[\s\S]{0,220}width:\s*auto/.test(read('css/app.css')), '');
}

console.log('RESULT: pass=' + pass + ' fail=' + fail);
process.exit(fail > 0 ? 1 : 0);

})();