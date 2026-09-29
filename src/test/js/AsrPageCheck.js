/*
 * 语音答题前端的检查程序（不是 JUnit，是 node 直接跑的脚本）。
 *
 * 为什么单独一份而不是扩 %TEMP% 里那个 PageCheck.js：那个 1200 行、覆盖六个页面，
 * 这一期只动 interview.html 一个页面。自包含一份不用把老沙箱一起搬进仓库。
 *
 * 跑法（仓库根目录）：
 *   node src/test/js/AsrPageCheck.js
 *
 * 它盯住四件事：
 *   1. app.js 的 api() 新增的「裸二进制」分支，以及 FormData / JSON 两条老路没被弄坏
 *   2. ★ 转写结果**追加**进答题框，不覆盖 —— 覆盖会把已经打的字悄悄吃掉
 *   3. ★ asrReset / 重录 走的是 asrCancel 而不是 asrStop ——
 *      走错的话用户放弃了录音，却还会白发一次 /api/asr/transcribe 请求
 *   4. interview.html 模板：**还是一个答题框**、按 micAvailable 显示麦克风、
 *      提交时挡住录音/转写中
 *
 * 这份只管**离线**那条（asr.js）。流式那条在 AsrStreamPageCheck.js，两份分开是因为
 * 两条路的替身环境完全不搭（这边装 MediaRecorder，那边装 AudioWorklet）。
 *
 * 第一版是「语音/打字两个模式」+ 只读转写块，那一版的断言（asrSubmitText 四种组合、
 * 语音模式下不给 textarea）已随设计一起删掉。后来加了流式，「模式」这个词回来了，
 * 但含义不一样：现在是**采集方式**（离线 / 流式），答题框仍然只有一个。
 * 所以反证式的断言从「不许出现 asrMode」改成「模式取值只能是 offline/stream、
 * 且不许再出现『改用打字』那种切换」—— 前者已经过时了，留着会把自己判红。
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

// ────────────────────────── 浏览器环境替身 ──────────────────────────

const store = {};
globalThis.localStorage = {
  getItem: k => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v); },
  removeItem: k => { delete store[k]; }
};

// 「被拒的请求」不去操作 location，这里只需要它存在
globalThis.location = { search: '', replace: () => {} };

globalThis.document = { createElement: () => ({ click() {}, remove() {} }), body: { appendChild() {} } };
globalThis.window = {};

globalThis.Blob = class {
  constructor(parts) { this.parts = parts || []; this.size = this.parts.length ? 1 : 0; }
};

// 请求记账
let requests = [];
let routes = {};
globalThis.fetch = async (url, opts = {}) => {
  requests.push({ url, opts });
  const body = routes[url];
  if (body === undefined) throw new Error('没给路由: ' + url);
  return {
    status: 200,
    json: async () => ({ code: 0, message: 'ok', data: body })
  };
};

// ────────────────────────── 载入 app.js / asr.js ──────────────────────────

Object.assign(globalThis, new Function(read('js/app.js') + `
  return { getToken, setToken, clearToken, api, toast, fmtScore };`)());

Object.assign(globalThis, new Function(read('js/asr.js') + `
  return { asrData, ASR_METHODS, asrToPcm16k, asrRms, ASR_TARGET_RATE };`)());

const toasts = [];
globalThis.toast = (msg, isError) => { toasts.push((isError ? 'ERR:' : '') + msg); };

/** 拼一个能用的 vm：data + methods（methods 要绑 this） */
function makeVm(overrides = {}) {
  const vm = Object.assign({ answer: '' }, asrData(), overrides);
  for (const [k, fn] of Object.entries(ASR_METHODS)) {
    vm[k] = typeof fn === 'function' ? fn.bind(vm) : fn;
  }
  // 页面里 micStart / micStop 是按 asrMode 分发的（interview.html）。这份检查只测离线那条，
  // 所以直接指向 asrStart / asrStop。**必须补**：抽了 ticker 之后「到上限自动停」调的是
  // this.micStop()，不补的话那条会因为 `this.micStop is not a function` 假红 ——
  // 而且报出来的是「没自动停」，看着像计时器坏了
  vm.micStart = () => vm.asrStart();
  vm.micStop = () => vm.asrStop();
  return vm;
}

// ══════════════════════ 1. app.js 的 api() 三条路 ══════════════════════

(async () => {

globalThis.setToken('t');

// 1a. 裸二进制（语音走这条）
requests = []; routes = { '/api/asr/transcribe?sampleRate=16000': { text: '你好' } };
await globalThis.api('/api/asr/transcribe?sampleRate=16000', {
  method: 'POST', body: new Int16Array([1, 2, 3]), contentType: 'application/octet-stream'
});
{
  const r = requests[0];
  check('★ api() 传 TypedArray 时 Content-Type 是 application/octet-stream',
    r && r.opts.headers['Content-Type'] === 'application/octet-stream',
    r && r.opts.headers['Content-Type']);
  check('★ api() 传 TypedArray 时 body 原样发出，没有被 JSON.stringify 成 "{}"',
    r && typeof r.opts.body !== 'string' && r.opts.body instanceof Int16Array,
    r && (typeof r.opts.body) + ' / ' + (r.opts.body && r.opts.body.constructor.name));
  check('裸二进制请求也带上了 Authorization',
    r && r.opts.headers['Authorization'] === 'Bearer t',
    r && r.opts.headers['Authorization']);
}

// 1b. 反证：普通对象还是走 JSON —— 新增分支不能把老路弄坏
requests = []; routes = { '/api/x': { ok: 1 } };
await globalThis.api('/api/x', { method: 'POST', body: { answer: '打的字' } });
{
  const r = requests[0];
  check('反证：普通对象的 body 仍然 JSON 序列化，Content-Type 仍是 application/json',
    r && r.opts.headers['Content-Type'] === 'application/json'
    && r.opts.body === JSON.stringify({ answer: '打的字' }),
    r && r.opts.headers['Content-Type'] + ' / ' + r.opts.body);
}

// 1c. 回归：FormData 原样传（简历上传那条路）
requests = []; routes = { '/api/resume/upload': { ok: 1 } };
const fd = new FormData();
fd.append('file', 'x');
await globalThis.api('/api/resume/upload', { method: 'POST', body: fd });
{
  const r = requests[0];
  check('回归：FormData 仍然原样传，不设 Content-Type（让浏览器自己带 boundary）',
    r && r.opts.body === fd && r.opts.headers['Content-Type'] === undefined,
    r && String(r.opts.headers['Content-Type']));
}

// ══════════════════════ 2. asrData 默认值 + 转写落进答题框 ══════════════════════

{
  const d = asrData();
  check('asrData 默认：idle / 120 秒 / 未确认可用，且没有 asrMode 这种模式字段',
    d.asrState === 'idle' && d.asrMaxSeconds === 120 && d.asrAvailable === false
    && !('asrMode' in d) && !('asrText' in d),
    JSON.stringify(d));
}

/**
 * 把「解码 → 重采样」这段的替身装好，让 asrTranscribe 能一路走到 fetch。
 *
 * 不能直接 stub asrToPcm16k：它是 asr.js 的**模块级函数**，asrTranscribe 闭包直接引用，
 * 挂到 globalThis 上不生效（改了也不报错，就是请求照发，看着像没改）。
 * 所以只能把它依赖的那几个浏览器 API 假装出来。
 */
function installDecoderEnv() {
  globalThis.Blob = class {
    constructor(parts) { this.parts = parts || []; this.size = this.parts.length ? 1 : 0; }
    async arrayBuffer() { return new ArrayBuffer(8); }
  };
  globalThis.window.AudioContext = function () {
    this.decodeAudioData = async () => ({ duration: 0.1 });
    this.close = () => {};
  };
  globalThis.OfflineAudioContext = function () {
    this.destination = {};
    this.createBufferSource = () => ({ connect() {}, start() {} });
    this.startRendering = async () => ({ getChannelData: () => new Float32Array(160) });
  };
}

// 2b. ★ 转写回来落进 answer：追加，不是覆盖
{
  installDecoderEnv();
  requests = [];
  routes = { '/api/asr/transcribe?sampleRate=16000': { text: '这是说的那段' } };

  const empty = makeVm({ answer: '' });
  await empty.asrTranscribe(new Blob([1]));
  check('★ 框里本来是空的 → 转写结果直接落进去，状态变 done',
    empty.answer === '这是说的那段' && empty.asrState === 'done',
    JSON.stringify([empty.answer, empty.asrState]));

  // 这条是这次改 UI 的核心：覆盖会把已经打的字**悄悄**吃掉
  const typed = makeVm({ answer: '先打的两句' });
  await typed.asrTranscribe(new Blob([1]));
  check('★ 框里已经打了字 → 转写结果是追加，打的字不能被吃掉',
    typed.answer === '先打的两句 这是说的那段', JSON.stringify(typed.answer));

  const blank = makeVm({ answer: '   ' });
  await blank.asrTranscribe(new Blob([1]));
  check('反证：框里只有空白时不追加前导空格（否则录一次多一个空格）',
    blank.answer === '这是说的那段', JSON.stringify(blank.answer));
}

// 2c. 转写失败时不动框里的内容（失败不该把用户的草稿也带走）
{
  installDecoderEnv();
  requests = [];
  routes = { '/api/asr/transcribe?sampleRate=16000': { text: '   ' } };
  const vm = makeVm({ answer: '打了半天的草稿' });
  await vm.asrTranscribe(new Blob([1]));
  check('★ 转写出空文本 → 框里内容原样不动，只给错误提示',
    vm.answer === '打了半天的草稿' && vm.asrState === 'idle' && vm.asrError.includes('没听到内容'),
    JSON.stringify([vm.answer, vm.asrState, vm.asrError]));
}

// ══════════════════════ 3. asrInit 的降级 ══════════════════════

// 3a. /status 说不支持 → 直接切打字，按钮不显示
{
  requests = []; routes = { '/api/asr/status': { available: false, reason: '没配模型' } };
  const vm = makeVm();
  await vm.asrInit();
  check('★ asrInit 拿到 available:false → 麦克风按钮不显示（答题框照常在，打字不受影响）',
    vm.asrAvailable === false, String(vm.asrAvailable));
}

// 3b. /status 说支持 → 显示麦克风，并把上限带过来
{
  requests = []; routes = { '/api/asr/status': { available: true, maxSeconds: 90 } };
  const vm = makeVm();
  await vm.asrInit();
  check('★ asrInit 拿到 available:true → 麦克风显示，且上限用后端给的 90 秒',
    vm.asrAvailable === true && vm.asrMaxSeconds === 90,
    JSON.stringify([vm.asrAvailable, vm.asrMaxSeconds]));
}

// 3c. /status 请求本身失败 → 当没有语音，不能把页面带崩
{
  globalThis.fetch = async (url, opts = {}) => { requests.push({ url, opts }); throw new Error('网络挂了'); };
  const vm = makeVm();
  await vm.asrInit();
  check('★ /status 请求失败时当作没有语音（麦克风不显示，答题框照常在）',
    vm.asrAvailable === false, String(vm.asrAvailable));
  // 恢复
  globalThis.fetch = async (url, opts = {}) => {
    requests.push({ url, opts });
    const body = routes[url];
    if (body === undefined) throw new Error('没给路由: ' + url);
    return { status: 200, json: async () => ({ code: 0, message: 'ok', data: body }) };
  };
}

// ══════════════════════ 4. ★ asrCancel 和 asrStop 的区别 ══════════════════════

/** 造一个能真进到「录音中」的环境 */
function installRecorderEnv(supportGetUserMedia = true) {
  const created = [];
  // node 自带的 navigator 是只读全局，直接 `globalThis.navigator = x` 是静默失败的
  // （非严格模式下不报错，值也不变）。这里必须用 defineProperty 才能真的换掉它 ——
  // 第一次写这份检查就是栽在这，四条断言全红但看起来像 asrStart 坏了
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    writable: true,
    value: {
      mediaDevices: supportGetUserMedia
        ? { getUserMedia: async () => ({ getTracks: () => [{ stop() {} }] }) }
        : null
    }
  });
  globalThis.MediaRecorder = function () {
    this.state = 'inactive';
    created.push(this);
    this.start = () => { this.state = 'recording'; };
    // 和真的一样：stop() 会触发 onstop（asrCancel 就是靠把 onstop 摘掉来丢弃这次录音）
    this.stop = () => { this.state = 'inactive'; if (this.onstop) this.onstop(); };
  };
  globalThis.MediaRecorder.created = created;
  globalThis.window.AudioContext = function () {
    this.createMediaStreamSource = () => ({ connect() {} });
    this.createAnalyser = () => ({ fftSize: 0, getByteTimeDomainData() {} });
    this.close = () => {};
  };
}

// 4a. 浏览器不支持录音 → 文案 + 没卡在 recording
{
  installRecorderEnv(false);
  const vm = makeVm({ asrAvailable: true });
  await vm.asrStart();
  check('不支持录音时给出路（文案里有「打字」），且状态没卡在 recording',
    vm.asrState === 'idle' && vm.asrError.includes('打字'),
    JSON.stringify([vm.asrState, vm.asrError]));
}

// 4b. ★ 录音中提交（asrReset）→ 丢掉录音，不发起转写
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true, answer: '之前打的' });
  await vm.asrStart();
  check('能进到「录音中」（替身环境装对了）', vm.asrState === 'recording', vm.asrState);

  // 用 spy 盯住 asrTranscribe：光看 fetch 记录不够 ——
  // 解码那步一旦走不通，就根本到不了 fetch，那样「没请求」可能是因为整条路都坏着
  let transcribed = 0;
  vm.asrTranscribe = () => { transcribed++; };

  vm.asrReset();
  check('★ 录音中调 asrReset → 不触发转写（走的是 asrCancel 不是 asrStop）',
    transcribed === 0, 'asrTranscribe 被调了 ' + transcribed + ' 次');
  check('★ 录音中调 asrReset → 状态回 idle、错误清空',
    vm.asrState === 'idle' && vm.asrError === '',
    JSON.stringify([vm.asrState, vm.asrError]));
  check('★ 录音中调 asrReset → 麦克风和计时器都放掉了',
    vm._asrRecorder === null && vm._asrTimer === null && vm._asrStream === null,
    JSON.stringify([vm._asrRecorder, vm._asrTimer, vm._asrStream]));
  check('★ 清录音状态**不碰答题框**（草稿是用户的东西，清状态顺手抹掉最气人）',
    vm.answer === '之前打的', JSON.stringify(vm.answer));
}

// 4c. 反证：「停止」这条路**必须**触发转写，否则 4b 的「没触发」可能只是因为整条路都坏着
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true });
  await vm.asrStart();
  let transcribed = 0;
  vm.asrTranscribe = () => { transcribed++; };
  vm.asrStop();
  check('反证：点「停止」→ 确实触发了转写（所以 4b 的「没触发」是有意义的）',
    transcribed === 1, 'asrTranscribe 被调了 ' + transcribed + ' 次');
}

// 4d. 录音中再点麦克风 = 重录：也是走 asrCancel，且不吃掉草稿
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true, answer: '打了半天的草稿' });
  await vm.asrStart();
  let transcribed = 0;
  vm.asrTranscribe = () => { transcribed++; };

  await vm.asrStart();   // 重录
  check('★ 录音中再点麦克风 → 丢掉上一次录音（不转写），并开了新的一次',
    transcribed === 0 && vm.asrState === 'recording'
    && MediaRecorder.created.length === 2,
    JSON.stringify([transcribed, vm.asrState, MediaRecorder.created.length]));
  check('★ 重录不清空答题框（否则说了半句想重说，打好的字就没了）',
    vm.answer === '打了半天的草稿', JSON.stringify(vm.answer));
  vm.asrCancel();
}

// 4e. 到上限自动停 —— 这条不能靠用户自己发现「已经说了几分钟」
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true, asrMaxSeconds: 0 });
  let transcribed = 0;
  vm.asrTranscribe = () => { transcribed++; };
  await vm.asrStart();
  await new Promise(r => setTimeout(r, 250));   // 计时器 100ms 一跳
  check('★ 到了上限会自动停并转写（不用用户自己数秒）',
    transcribed === 1 && vm.asrState === 'transcribing',
    JSON.stringify([transcribed, vm.asrState]));
  vm.asrCancel();
}

// ══════════════════════ 5. interview.html 模板 ══════════════════════

{
  const html = read('interview.html');

  const asrSrc = read('js/asr.js');
  const textareas = (html.match(/<textarea/g) || []).length;

  check('页面引了 asr.js',
    /<script src="js\/asr\.js"><\/script>/.test(html), '');

  check('★ 就一个答题框（textarea 只有 1 个），不是「两种模式各一套」',
    textareas === 1, 'textarea 出现 ' + textareas + ' 次');

  // 那个「中间不超过 N 个字符」只是「它们在同一个 answer-bar 里」的近似写法。
// 加了模式下拉之后这段长到 1300 多字符，从 900 放宽到 2000 —— 真正的断言是前面那三组
  // indexOf 的顺序，窗口只是别让它匹配到页面别处的 icon-btn
  check('★ 麦克风在那个框里面（answer-box → textarea → answer-bar → icon-btn 的顺序）',
    html.indexOf('class="answer-box"') < html.indexOf('<textarea')
    && html.indexOf('<textarea') < html.indexOf('class="answer-bar"')
    && html.indexOf('class="answer-bar"') < html.indexOf('icon-btn')
    && /class="answer-bar"[\s\S]{0,2000}icon-btn/.test(html), '');

  // 加了流式之后显示条件从 asrAvailable 变成 micAvailable()（按当前模式挑对应的那个
  // available）。断的仍然是「不可用时静默隐藏」这件事，只是换了个名字
  check('★ 麦克风的显示条件走 micAvailable()（不可用时静默隐藏）',
    /v-else-if="micAvailable"/.test(html), '');
  check('★ 点了麦克风走 micStart / micStop，不直接调 asrStart / asrStop '
    + '（直连的话流式模式下会去停一个没在跑的 MediaRecorder）',
    /@click="micStart"/.test(html) && /@click="micStop"/.test(html)
    && !/@click="asrStart"/.test(html) && !/@click="asrStop"/.test(html), '');

  // 先剥掉 HTML 注释再查：模板里的注释**故意**提到了那个被砍掉的按钮
// （「这里不再需要『改用打字』那个逃生按钮」），那是解释不是代码。
  // 第一次写这条断言没剥注释，自己把自己判红了
  const htmlBare = html.replace(/<!--[\s\S]*?-->/g, '');
  check('★ 没有「改用打字 / 改用语音」这类模式切换按钮',
    !/改用打字|改用语音/.test(htmlBare), '');

  check('★ 没有只读转写块了（.asr-text / .asr-result 都不该再有）',
    !html.includes('class="asr-text"') && !html.includes('class="asr-result"'), '');

  // 「模式」这个词回来了，但含义变了：现在是**采集方式**（离线 / 流式），
  // 不是「语音还是打字」—— 答题框始终只有一个（上面那条 textarea 计数盯着的就是这件事）。
  // 所以这里断的是「取值只能是这两个」，而不是「不许出现 asrMode」
  const streamSrc = read('js/asr-stream.js');
  check('★ 模式取值只有 offline / stream —— 是「怎么采集」，不是「语音还是打字」',
    /ASR_STREAM_MODES = \{ offline: '离线', stream: '流式' \}/.test(streamSrc), '');
  check('★ 模式下拉就两个选项，且没有 typing 之类的第三种',
    /<option value="stream"/.test(html) && /<option value="offline"/.test(html)
    && !/value="typing"/.test(html), '');
  // 分发（micStart / micStop）在页面里，asr.js 只在 asrInit 里碰 asrMode：
  // 读一次判断要不要回落 + 赋值一次。第二处分支就说明「怎么走」被写进了 asr.js，
  // 那就该搬回页面去 —— 那条路少加载 asr-stream.js 就是运行时 undefined
  check('★ 离线那条不按模式分发，只在 asrInit 里读一次 asrMode 做回落',
    /asrInit\(\)[\s\S]*?this\.asrMode === 'stream'[\s\S]*?this\.asrMode = 'offline'/.test(asrSrc)
    && (asrSrc.match(/\.asrMode/g) || []).length === 2, '');

  check('★ 提交按钮的禁用条件用 canSubmit',
    html.includes(':disabled="!canSubmit"'), '');

  check('★ canSubmit 挡住「录音中」和「转写中」（否则提交清空 answer，转写结果落到下一题）',
    /canSubmit\(\)\s*\{[\s\S]{0,240}asrState !== 'recording'[\s\S]{0,120}asrState !== 'transcribing'/
      .test(html), '');

  check('data() 里展开了 asrData()',
    /data\(\)[\s\S]{0,600}\.\.\.asrData\(\)/.test(html), '');
  check('methods 里展开了 ASR_METHODS',
    /methods:\s*\{[\s\S]{0,120}\.\.\.ASR_METHODS/.test(html), '');
  check('computed 里 submitText 取 answer，不再接 asrSubmitText',
    /submitText\(\)\s*\{\s*return \(this\.answer \|\| ''\)\.trim\(\);/.test(html)
    && !/asrSubmitText/.test(html), '');

  check('★ 旧的 lastAnswer data 字段已被删掉（留着会让人以为还能用）',
    !/lastAnswer:\s*''/.test(html), '');
}

// 清掉可能还挂着的计时器，避免 node 不退出
process.exit(fail > 0 ? 1 : 0);

})();