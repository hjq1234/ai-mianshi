/* 全局工具。所有页面共用，不用打包工具，直接 <script src="js/app.js"> */

const TOKEN_KEY = 'ai-mianshi-token';

function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

/** 未登录就踢回登录页。每个需要登录的页面在 onMounted 里调一次 */
function requireLogin() {
  if (!getToken()) {
    location.replace('login.html');
    return false;
  }
  return true;
}

function logout() {
  clearToken();
  location.replace('login.html');
}

/**
 * 统一的接口调用。
 * - 自动带 Authorization 头
 * - 401 自动清 token 跳登录
 * - code !== 0 抛异常，调用方 catch 后提示
 * - body 自动 JSON 序列化；FormData 原样传（文件上传）
 */
async function api(path, options = {}) {
  const opts = { method: options.method || 'GET', headers: {} };
  const isForm = options.body instanceof FormData;

  if (options.body !== undefined) {
    if (isForm) {
      opts.body = options.body;
    } else {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(options.body);
    }
  }

  const token = getToken();
  if (token) {
    opts.headers['Authorization'] = 'Bearer ' + token;
  }

  let response;
  try {
    response = await fetch(path, opts);
  } catch (e) {
    throw new Error('网络请求失败，请检查服务是否已启动');
  }

  if (response.status === 401) {
    clearToken();
    location.replace('login.html');
    throw new Error('登录已过期');
  }

  const json = await response.json();
  if (json.code !== 0) {
    throw new Error(json.message || '请求失败');
  }
  return json.data;
}

/** epoch 毫秒 → 2026-09-28 15:30 */
function fmtTime(millis) {
  if (!millis) return '-';
  const d = new Date(millis);
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
       + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fmtScore(score) {
  return score === null || score === undefined ? '-' : Number(score).toFixed(1);
}

/** 分数配色：8 分以上绿、4 分以下红、中间黄 */
function scoreClass(score) {
  if (score === null || score === undefined) return '';
  if (score >= 8) return 'good';
  if (score < 4) return 'bad';
  return 'mid';
}

/** 顶部飘一条提示，2.5 秒后自动消失 */
function toast(message, isError = false) {
  let el = document.getElementById('global-toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'global-toast';
    document.body.appendChild(el);
  }
  el.textContent = message;
  el.className = 'toast show' + (isError ? ' toast-error' : '');
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.className = 'toast'; }, 2500);
}

/** 最小的 markdown 渲染：标题、粗体、列表、段落。报告是 LLM 出的，只用到这些 */
function renderMarkdown(text) {
  if (!text) return '';
  const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return esc(text)
    .replace(/^### (.+)$/gm, '<h4>$1</h4>')
    .replace(/^## (.+)$/gm, '<h3>$1</h3>')
    .replace(/^# (.+)$/gm, '<h2>$1</h2>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/^[-*] (.+)$/gm, '<li>$1</li>')
    // 必须贪婪地把「连续的 li」一次包住。写成非贪婪 /(<li>[\s\S]*?<\/li>)/g 的话
    // 每个 li 会各自包一层 ul，两条并列的列表项就渲染成两个断开的列表（中间还多一道空档）。
    .replace(/(<li>.*<\/li>(\n|$))+/g, m => '<ul>' + m.replace(/\n$/, '') + '</ul>')
    .replace(/\n{2,}/g, '</p><p>')
    .replace(/^/, '<p>')
    .replace(/$/, '</p>');
}

/**
 * 顶部导航渲染成统一的一段 HTML，省得六个页面各写一遍。
 * 页面里这样用：`<div v-html="topbar"></div>`，data 里 `topbar: topbarHtml('index.html')`。
 *
 * 面试进行页（interview.html）不用这个——它顶栏要显示题号和「结束面试」，那是活的。
 */
function topbarHtml(active) {
  const items = [
    ['index.html', '开始面试'],
    ['history.html', '面试记录'],
    ['resume.html', '我的简历']
  ];
  const links = items.map(([href, label]) =>
    `<a href="${href}" class="${href === active ? 'active' : ''}">${label}</a>`).join('');
  return `<div class="topbar">
    <span class="brand">AI 模拟面试</span>
    <nav>${links}</nav>
    <span class="user"><span id="nav-nickname" class="muted"></span>
      <button class="small" onclick="logout()">退出</button></span>
  </div>`;
}

/** 拉一次 /api/auth/me 填昵称。失败不打断页面 */
async function fillNickname() {
  try {
    const me = await api('/api/auth/me');
    const el = document.getElementById('nav-nickname');
    if (el) el.textContent = me.nickname || me.username || '';
  } catch (e) {
    /* 401 时 api() 已经跳登录页了，这里静默即可 */
  }
}

const DIFFICULTY_OPTIONS = ['简单', '中等', '困难'];

const DIMENSION_LABELS = {
  accuracy: '准确性',
  depth: '深度',
  clarity: '表达',
  practice: '实践',
  problemSolving: '解题思路'
};

/* ────────────────────────── 复盘导出 ────────────────────────── */

/** Windows 文件名里 / \ : * ? " < > | 都是非法的，统一换掉 */
function safeFileName(name) {
  const cleaned = String(name).replace(/[\\/:*?"<>|]/g, '-').trim();
  return cleaned || 'export';
}

/** 表格单元格：| 会把表格撑破，换行会把一行拆成两行 */
function mdCell(text) {
  return String(text === null || text === undefined ? '' : text)
    .replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

/**
 * 复盘详情 → Markdown 全文。
 *
 * 数据全部来自 /api/interview/{id}/detail，所以导出不需要新接口，
 * 也就不存在「<a href> 下载链接带不上 Authorization 头」这个问题。
 */
function buildReportMarkdown(detail) {
  const action = a => NEXT_ACTION_LABELS[a] || a || '-';
  const dialogues = detail.dialogues || [];
  const topics = [...new Set(dialogues.map(d => d.topic).filter(Boolean))];
  const L = [];

  L.push(`# ${detail.position || '技术面试'} · 面试复盘`, '');
  L.push(`- 公司：${detail.company || '-'}`);
  L.push(`- 方向：${detail.domain || '-'}`);
  L.push(`- 难度：${detail.difficulty || '-'}`);
  L.push(`- 时间：${fmtTime(detail.createdAt)}`);
  L.push(`- 状态：${detail.status === 'finished' ? '已结束' : '进行中'}`);
  L.push(`- 总分：${fmtScore(detail.totalScore)} / 10（共 ${dialogues.length} 题）`);
  L.push(`- 覆盖话题：${topics.join('、') || '-'}`);
  if (detail.error) L.push(`- 备注：${detail.error}`);
  L.push('');

  // 决策链：一眼看清每题走的是哪个分支、换到哪儿去了
  L.push('## 决策链', '');
  L.push('| 题号 | 话题 | 难度 | 得分 | 图的分支 | 说明 |');
  L.push('|---|---|---|---|---|---|');
  for (const d of dialogues) {
    const note = (d.comment || '') + (d.nextTopic ? `（换到：${d.nextTopic}）` : '');
    L.push(`| ${d.seq} | ${mdCell(d.topic || '-')} | ${mdCell(d.difficulty || '-')} `
         + `| ${fmtScore(d.score)} | ${action(d.nextAction)} | ${mdCell(note)} |`);
  }
  L.push('');

  L.push('## 逐题详情', '');
  for (const d of dialogues) {
    L.push(`### 第 ${d.seq} 题 · ${d.topic || '综合'} · ${d.difficulty || '-'} · ${fmtScore(d.score)} 分`, '');
    L.push(`**问：** ${d.question || '-'}`, '');
    L.push('**答：**', '', (d.answer || '（未作答）').trim(), '');
    if (d.comment) L.push(`**评语：** ${d.comment}`, '');
    const dims = d.dimensions || {};
    const keys = Object.keys(dims);
    if (keys.length) {
      // 没有项目背景的题 practice 是 null，这里显示成 '-'，不是 0 分
      L.push('**五维：** ' + keys
        .map(k => `${DIMENSION_LABELS[k] || k} ${fmtScore(dims[k])}`).join(' · '), '');
    }
    L.push(`**下一步：** → ${action(d.nextAction)}`
      + (d.nextTopic ? `（换到：${d.nextTopic}）` : ''), '');
  }

  L.push('## AI 综合报告', '');
  L.push(detail.report || '（这场面试还没结束，没有综合报告）', '');
  L.push('---', `导出时间：${fmtTime(Date.now())}`);
  return L.join('\n');
}

/** Blob + <a download>。不用 window.open：那样指定不了文件名，也绕不开鉴权头的问题 */
function downloadTextFile(filename, text, mime = 'text/markdown;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 立刻 revoke 在部分浏览器上会打断下载，等一拍再释放
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const NEXT_ACTION_LABELS = {
  deepen: '深入追问',
  continue: '换个角度',
  lower: '降低难度',
  switch: '更换话题',
  end: '结束面试'
};