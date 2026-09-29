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
 *   2. asr.js 的状态机：asrSubmitText 四种组合、asrInit 的降级
 *   3. ★ asrUseTyping / asrReset 走的是 asrCancel 而不是 asrStop ——
 *      走错的话用户放弃了录音，却还会白发一次 /api/asr/transcribe 请求
 *   4. interview.html 模板：语音模式下是只读块不是 textarea、麦克风按钮的显示条件
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

// ══════════════════════ 2. asrData 默认值与 asrSubmitText ══════════════════════

{
  const d = asrData();
  check('asrData 默认：idle / voice / 120 秒 / 未确认可用',
    d.asrState === 'idle' && d.asrMode === 'voice'
    && d.asrMaxSeconds === 120 && d.asrAvailable === false,
    JSON.stringify([d.asrState, d.asrMode, d.asrMaxSeconds, d.asrAvailable]));
}

// 该提交哪个字符串：语音模式下取转写文本，打字模式下取 textarea
const submitCases = [
  ['voice', 'done', '说了的', '打字的', '说了的', '语音转写完了 → 提交转写文本'],
  ['voice', 'idle', '说了的', '打字的', '打字的', '语音没录完 → 别提交上一条转写'],
  ['typing', 'done', '说了的', '打字的', '打字的', '已改用打字 → 提交 textarea'],
  ['typing', 'idle', '', '  ', '', '纯空白不算内容']
];
for (const [mode, state, text, answer, want, why] of submitCases) {
  const vm = makeVm({ asrMode: mode, asrState: state, asrText: text, answer });
  check('asrSubmitText [' + mode + '/' + state + '] → "' + want + '"（' + why + '）',
    vm.asrSubmitText() === want, '"' + vm.asrSubmitText() + '"');
}

// ══════════════════════ 3. asrInit 的降级 ══════════════════════

// 3a. /status 说不支持 → 直接切打字，按钮不显示
{
  requests = []; routes = { '/api/asr/status': { available: false, reason: '没配模型' } };
  const vm = makeVm();
  await vm.asrInit();
  check('★ asrInit 拿到 available:false → asrMode 变 typing（打字的路径必须还在）',
    vm.asrAvailable === false && vm.asrMode === 'typing',
    JSON.stringify([vm.asrAvailable, vm.asrMode]));
}

// 3b. /status 说支持 → 语音模式，并把上限带过来
{
  requests = []; routes = { '/api/asr/status': { available: true, maxSeconds: 90 } };
  const vm = makeVm();
  await vm.asrInit();
  check('★ asrInit 拿到 available:true → asrMode 是 voice，且上限用后端给的 90 秒',
    vm.asrAvailable === true && vm.asrMode === 'voice' && vm.asrMaxSeconds === 90,
    JSON.stringify([vm.asrAvailable, vm.asrMode, vm.asrMaxSeconds]));
}

// 3c. /status 请求本身失败 → 当没有语音，不能把页面带崩
{
  globalThis.fetch = async (url, opts = {}) => { requests.push({ url, opts }); throw new Error('网络挂了'); };
  const vm = makeVm();
  await vm.asrInit();
  check('★ /status 请求失败时当作没有语音（打字的路径不受影响）',
    vm.asrAvailable === false && vm.asrMode === 'typing', vm.asrMode);
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
  const vm = makeVm({ asrAvailable: true, asrMode: 'voice' });
  await vm.asrStart();
  check('不支持录音时给出路（文案里有「打字」），且状态没卡在 recording',
    vm.asrState === 'idle' && vm.asrError.includes('打字'),
    JSON.stringify([vm.asrState, vm.asrError]));
}

// 4b. ★ 录音中「改用打字」→ 丢掉录音，不发起转写
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true, asrMode: 'voice' });
  await vm.asrStart();
  check('能进到「录音中」（替身环境装对了）', vm.asrState === 'recording', vm.asrState);

  // 用 spy 盯住 asrTranscribe：光看 fetch 记录不够 ——
  // 沙箱里解码那步会先抛异常，走不到 fetch，那样「没请求」可能是因为整条路都坏着
  let transcribed = 0;
  vm.asrTranscribe = () => { transcribed++; };

  vm.asrUseTyping();
  check('★ 录音中点「改用打字」→ 不触发转写（走的是 asrCancel 不是 asrStop）',
    transcribed === 0, 'asrTranscribe 被调了 ' + transcribed + ' 次');
  check('★ 录音中点「改用打字」→ 状态回 idle、模式变 typing',
    vm.asrState === 'idle' && vm.asrMode === 'typing',
    JSON.stringify([vm.asrState, vm.asrMode]));
  check('★ 录音中点「改用打字」→ 麦克风和计时器都放掉了',
    vm._asrRecorder === null && vm._asrTimer === null && vm._asrStream === null,
    JSON.stringify([vm._asrRecorder, vm._asrTimer, vm._asrStream]));
}

// 4c. 反证：「停止」这条路**必须**触发转写，否则 4b 可能只是因为整条路都坏着
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true, asrMode: 'voice' });
  await vm.asrStart();
  let transcribed = 0;
  vm.asrTranscribe = () => { transcribed++; };
  vm.asrStop();
  check('反证：点「停止」→ 确实触发了转写（所以 4b 的「没触发」是有意义的）',
    transcribed === 1, 'asrTranscribe 被调了 ' + transcribed + ' 次');
}

// 4d. 提交后 asrReset 也走 asrCancel，不留下一次多余的转写
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true, asrMode: 'voice', asrState: 'done', asrText: '上一题的' });
  let transcribed = 0;
  vm.asrTranscribe = () => { transcribed++; };
  vm.asrReset();
  check('★ asrReset 清干净转写文本和状态（下一题不挂着上一题的转写）',
    vm.asrState === 'idle' && vm.asrText === '' && vm.asrError === '' && transcribed === 0,
    JSON.stringify([vm.asrState, vm.asrText, transcribed]));
}

// 4e. 到上限自动停 —— 这条不能靠用户自己发现「已经说了两分钟」
{
  installRecorderEnv(true);
  requests = []; routes = {};
  const vm = makeVm({ asrAvailable: true, asrMode: 'voice', asrMaxSeconds: 0 });
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

  check('页面引了 asr.js',
    /<script src="js\/asr\.js"><\/script>/.test(html), '');

  check('★ 语音模式下转写结果落在只读块（.asr-text），不是 textarea',
    html.includes('class="asr-text"') && html.includes("asrMode === 'voice' && asrState === 'done'"),
    '');

  check('★ 只有打字分支里有 textarea（语音模式不给编辑框——「练口语」那条前提的落点）',
    (html.match(/<textarea/g) || []).length === 1
    && /v-if="asrMode === 'typing' \|\| !asrAvailable"/.test(html),
    'textarea 出现 ' + (html.match(/<textarea/g) || []).length + ' 次');

  check('★ 麦克风按钮的显示条件是 asrAvailable && voice 模式（不可用时静默隐藏）',
    /v-if="asrAvailable && asrMode === 'voice'"/.test(html) && html.includes('🎤 录音'), '');

  check('★ 提交按钮的禁用条件用 submitText，不是 answer（语音模式下 answer 一直是空的）',
    html.includes(':disabled="submitting || !submitText"')
    && !html.includes(':disabled="submitting || !answer.trim()"'), '');

  check('★ 每条错误旁边都有「改用打字」这个出口',
    /class="asr-error"[\s\S]{0,220}改用打字/.test(html), '');

  check('data() 里展开了 asrData()',
    /data\(\)[\s\S]{0,600}\.\.\.asrData\(\)/.test(html), '');
  check('methods 里展开了 ASR_METHODS',
    /methods:\s*\{[\s\S]{0,120}\.\.\.ASR_METHODS/.test(html), '');
  check('computed 里有 submitText，接到 asrSubmitText',
    /submitText\(\)\s*\{\s*return this\.asrSubmitText\(\);/.test(html), '');

  check('★ 旧的 lastAnswer data 字段已被删掉（留着会让人以为还能用）',
    !/lastAnswer:\s*''/.test(html), '');
}

// 清掉可能还挂着的计时器，避免 node 不退出
process.exit(fail > 0 ? 1 : 0);

})();