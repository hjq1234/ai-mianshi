/* 复盘页的四张图。
 *
 * 为什么单独一个文件而不是写进 report.html 的内联脚本：四张图加起来两百多行，
 * 塞进去那个页面要破五百行。仍然是零构建（多一个 script 标签而已）。
 *
 * 依赖：app.js（DIMENSION_LABELS / NEXT_ACTION_LABELS / fmtScore、以及样式里的 CSS 变量）
 * 图表库：ECharts，CDN 引入。这里只碰 echarts.init().setOption()，不碰别的。
 */

/** 图表用的色板，跟 app.css 的变量保持一致 */
const CHART_COLORS = {
  primary: '#2b6cff',
  history: '#9aa4b2',
  warn: '#d97706',
  border: '#e3e6ea',
  muted: '#6b7280'
};

/** 五维的固定顺序。雷达图的五个轴按这个顺序摆，不能跟着对象的 key 顺序乱跑 */
const CHART_DIMENSIONS = Object.keys(DIMENSION_LABELS);

/** 图里的节点名 → 中文。没列到的（将来加了新节点）原样显示英文 */
const NODE_LABELS = {
  start: '开场',
  question: '出题',
  wait_answer: '等待作答',
  evaluate: '评分',
  deepen: '深入',
  continue: '换角度',
  lower: '降难度',
  switch: '换话题',
  end_loop: '收尾',
  end: '结束'
};

/** 分支的 to_node 是「下一个节点名」，不是 nextAction，end_loop 要换算一下才能查标签 */
const BRANCH_TO_ACTION = { end_loop: 'end' };

function escapeHtml(text) {
  return String(text === null || text === undefined ? '' : text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function branchLabel(toNode) {
  if (!toNode) return '';
  const action = BRANCH_TO_ACTION[toNode] || toNode;
  return NEXT_ACTION_LABELS[action] || toNode;
}

/** 雷达/柱状图的 tooltip 数值统一走这个，别显示成 6.833333333333333 */
function oneDecimal(value) {
  return value === null || value === undefined ? '-' : Number(value).toFixed(1);
}

/**
 * 图表库没加载出来（CDN 打不开、离线）。
 * 单独判一下是因为这种情况 echarts.init 会抛 ReferenceError，
 * 报「图表库没加载」比报「echarts is not defined」对使用者有用得多。
 */
function chartLibMissing(el) {
  if (typeof echarts !== 'undefined') {
    return false;
  }
  if (el) {
    el.innerHTML = '<div class="chart-fail">图表库没加载出来（ECharts CDN 打不开？），'
                 + '页面其余部分不受影响</div>';
  }
  return true;
}

/**
 * 每张图各自 try/catch。图表是增强，任何一张挂了都不能让整页挂。
 * 失败了就把容器换成一行说明，而不是留一块白。
 */
function guardChart(el, draw) {
  try {
    draw();
  } catch (e) {
    if (el) {
      el.innerHTML = '<div class="chart-fail">这张图渲染失败：' + escapeHtml(e && e.message || e) + '</div>';
    }
  }
}

/* ────────────────────────── ① 能力雷达图 ────────────────────────── */

/**
 * @param el            容器
 * @param current       本场五维均分，形如 {accuracy: 7.2, practice: null}
 * @param history       历史五维均分，没有历史时传 null
 * @param historyCount  参与统计的场次，0 表示不画历史那条
 */
function renderRadar(el, { current, history, historyCount }) {
  if (!el || chartLibMissing(el)) return;

  guardChart(el, () => {
    // 某维是 null（纯概念题的 practice）时按 0 画 —— 雷达没有「断点」这个概念，
    // 页面上会另有一行小字说明「本场无实践分，图中按 0 显示」，不假装有分
    const toValues = (source) => CHART_DIMENSIONS.map((key) => {
      const value = source ? source[key] : null;
      return value === null || value === undefined ? 0 : Number(value);
    });

    const hasHistory = historyCount > 0 && history && Object.keys(history).length > 0;
    const series = [{
      name: '本场',
      value: toValues(current),
      lineStyle: { width: 2, color: CHART_COLORS.primary },
      itemStyle: { color: CHART_COLORS.primary },
      areaStyle: { opacity: 0.18 }
    }];
    if (hasHistory) {
      series.push({
        name: '历史均分（' + historyCount + ' 场）',
        value: toValues(history),
        lineStyle: { type: 'dashed', color: CHART_COLORS.history },
        itemStyle: { color: CHART_COLORS.history }
      });
    }

    echarts.init(el).setOption({
      color: [CHART_COLORS.primary, CHART_COLORS.history],
      tooltip: { valueFormatter: oneDecimal },
      legend: { bottom: 0, itemWidth: 14, textStyle: { color: CHART_COLORS.muted } },
      radar: {
        indicator: CHART_DIMENSIONS.map((key) => ({ name: DIMENSION_LABELS[key], max: 10 })),
        radius: '62%',
        center: ['50%', '46%'],
        axisName: { color: CHART_COLORS.muted, fontSize: 12 },
        splitLine: { lineStyle: { color: CHART_COLORS.border } },
        splitArea: { areaStyle: { color: ['#ffffff', '#fafbfc'] } },
        axisLine: { lineStyle: { color: CHART_COLORS.border } }
      },
      series: [{ type: 'radar', symbolSize: 5, data: series }]
    });
  });
}

/* ────────────────────────── ② 分数趋势图 ────────────────────────── */

/**
 * 折线 + 均分虚线，并把「同一个话题的连续几题」用背景色带标出来。
 *
 * 为什么是话题色带而不是「标出 switch 的位置」：色带直接把话题分段画出来了，
 * 「第 3~5 题都在 JVM 上、第 6 题开始聊并发」一眼可见——这正是当初那个
 * 「一直换个角度、换到最后题目重复」的问题在复盘时该看到的东西。
 */
function renderScoreTrend(el, dialogues) {
  if (!el || chartLibMissing(el)) return;

  guardChart(el, () => {
    const list = dialogues || [];
    if (!list.length) {
      el.innerHTML = '<div class="chart-fail">这场还没答完，没有分数曲线</div>';
      return;
    }

    const scored = list.filter((d) => d.score !== null && d.score !== undefined);
    const average = scored.length
      ? scored.reduce((sum, d) => sum + d.score, 0) / scored.length
      : null;

    // 连续同话题的题归成一段，段内交替底色
    const bands = [];
    list.forEach((d, i) => {
      const topic = d.topic || '综合';
      const last = bands[bands.length - 1];
      if (last && last.topic === topic) {
        last.end = d.seq;
      } else {
        bands.push({ topic, start: d.seq, end: d.seq, index: bands.length });
      }
    });

    echarts.init(el).setOption({
      grid: { left: 8, right: 20, top: 34, bottom: 8, containLabel: true },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const d = list[params[0].dataIndex];
          if (!d) return '';
          const lines = [
            '第 ' + d.seq + ' 题 · ' + (d.topic || '综合') + ' · ' + (d.difficulty || '-'),
            '得分 ' + oneDecimal(d.score) + '　分支 ' + branchLabel(d.nextAction)
          ];
          if (d.comment) {
            lines.push('<div style="max-width:280px;white-space:normal">' + escapeHtml(d.comment) + '</div>');
          }
          return lines.join('<br>');
        }
      },
      xAxis: {
        type: 'category',
        data: list.map((d) => String(d.seq)),
        name: '题号',
        nameTextStyle: { color: CHART_COLORS.muted },
        axisLine: { lineStyle: { color: CHART_COLORS.border } },
        axisLabel: { color: CHART_COLORS.muted }
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 10,
        splitLine: { lineStyle: { color: CHART_COLORS.border } },
        axisLabel: { color: CHART_COLORS.muted }
      },
      series: [{
        type: 'line',
        data: list.map((d) => d.score),
        symbolSize: 7,
        itemStyle: { color: CHART_COLORS.primary },
        lineStyle: { width: 2, color: CHART_COLORS.primary },
        label: {
          show: true,
          position: 'top',
          formatter: (p) => oneDecimal(p.value),
          color: CHART_COLORS.muted,
          fontSize: 11
        },
        markArea: bands.length > 1 ? {
          silent: true,
          itemStyle: { color: 'rgba(43,108,255,0.05)' },
          label: {
            show: true,
            position: 'insideTop',
            color: CHART_COLORS.muted,
            fontSize: 11,
            formatter: (p) => p.data.name
          },
          data: bands.map((b) => [
            { xAxis: String(b.start), name: b.topic, itemStyle: { color: b.index % 2 ? 'rgba(43,108,255,0.06)' : 'transparent' } },
            { xAxis: String(b.end) }
          ])
        } : undefined,
        markLine: average === null ? undefined : {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: CHART_COLORS.warn },
          label: {
            formatter: '均分 ' + oneDecimal(average),
            color: CHART_COLORS.warn,
            position: 'insideEndTop'
          },
          data: [{ yAxis: average }]
        }
      }]
    });
  });
}

/* ────────────────────────── ③ 话题覆盖度 ────────────────────────── */

function renderTopicCoverage(el, dialogues) {
  if (!el || chartLibMissing(el)) return;

  guardChart(el, () => {
    const list = dialogues || [];
    const stats = new Map();
    for (const d of list) {
      const topic = d.topic || '综合';
      const row = stats.get(topic) || { topic, count: 0, sum: 0, scored: 0 };
      row.count++;
      if (d.score !== null && d.score !== undefined) {
        row.sum += d.score;
        row.scored++;
      }
      stats.set(topic, row);
    }
    if (!stats.size) {
      el.innerHTML = '<div class="chart-fail">这场还没答完，没有话题数据</div>';
      return;
    }

    // y 轴是从下往上画的，所以先按题数降序、再倒过来喂进去，最大的才在上面
    const rows = [...stats.values()]
      .sort((a, b) => b.count - a.count || a.topic.localeCompare(b.topic))
      .reverse();

    echarts.init(el).setOption({
      grid: { left: 8, right: 96, top: 10, bottom: 8, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params) => {
          const row = rows[params[0].dataIndex];
          return row.topic + '<br>' + row.count + ' 题'
               + (row.scored ? '　均分 ' + oneDecimal(row.sum / row.scored) : '　无评分');
        }
      },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: CHART_COLORS.border } },
        axisLabel: { color: CHART_COLORS.muted, formatter: '{value} 题' }
      },
      yAxis: {
        type: 'category',
        data: rows.map((r) => r.topic),
        axisTick: { show: false },
        axisLine: { lineStyle: { color: CHART_COLORS.border } },
        axisLabel: { color: CHART_COLORS.muted }
      },
      series: [{
        type: 'bar',
        barWidth: 16,
        data: rows.map((r) => ({ value: r.count, avg: r.scored ? r.sum / r.scored : null })),
        itemStyle: { color: CHART_COLORS.primary, borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          color: CHART_COLORS.muted,
          fontSize: 12,
          formatter: (p) => p.value + ' 题 · 均分 ' + oneDecimal(p.data.avg)
        }
      }]
    });
  });
}

/* ────────────────────────── ④ 图执行路径（泳道） ────────────────────────── */

/**
 * 每轮一行的时间线。**故意不用 ECharts**：这东西本质是「表格 + 色块」，
 * 不是坐标图。ECharts 的 custom series 要写 renderItem 那套坐标数学（约 60 行且难读），
 * 而 flex + 百分比宽度二十行就够了，还能直接复用现成的 .tag / .muted 配色。
 *
 * 返回值是 HTML 字符串，由 report.html 用 v-html 挂上去。
 *
 * ★ 色块宽度 = 该节点耗时，**不含你作答时的等待**：等待期间没有 trace 记录，
 * 所以整场的「用时」用的是记录的时间跨度，不在这里加总。
 */
function traceSwimlaneHtml(traces) {
  const list = (traces || []).slice().sort((a, b) => a.seq - b.seq);
  if (!list.length) {
    return '<div class="chart-fail">这场面试没有图执行记录</div>';
  }

  // 按轮分组，保持 seq 顺序
  const byRound = new Map();
  for (const t of list) {
    const round = t.round === null || t.round === undefined ? 0 : t.round;
    if (!byRound.has(round)) byRound.set(round, []);
    byRound.get(round).push(t);
  }

  /**
   * 相邻的同名节点合并成一个色块。
   * 两种重复都是实现细节、画出来只会让人困惑：
   *  - wait_answer 有两条（挂起时一条 suspend、恢复时一条 normal）
   *  - evaluate 有两条（节点本身 + 紧接着的分支决策行，b 行才带去向）
   */
  const mergeNodes = (rows) => {
    const blocks = [];
    for (const t of rows) {
      const last = blocks[blocks.length - 1];
      if (last && last.node === t.nodeName) {
        last.cost += t.costMs || 0;
        last.toNode = t.toNode || last.toNode;
        if (t.nodeType === 'branch') last.branch = true;
        if (t.status === 'suspend') last.suspend = true;
        if (t.status === 'error') last.error = true;
        continue;
      }
      blocks.push({
        node: t.nodeName,
        cost: t.costMs || 0,
        toNode: t.toNode,
        branch: t.nodeType === 'branch',
        suspend: t.status === 'suspend',
        error: t.status === 'error',
        status: t.status
      });
    }
    return blocks;
  };

  const rounds = [...byRound.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([round, rows]) => {
      const blocks = mergeNodes(rows);
      return {
        round,
        blocks,
        total: blocks.reduce((sum, b) => sum + b.cost, 0),
        branch: blocks.map((b) => b.toNode).filter(Boolean).pop() || null
      };
    });

  // 以「最慢的一轮」为 100%，这样各行之间能横向比快慢。
  // 全是 0 耗时（极少见）时退化成等宽，免得画出一排看不见的点
  const refMax = Math.max(...rounds.map((r) => r.total));
  const widthOf = (cost, count) => refMax > 0
    ? Math.max(cost / refMax * 100, 0.8)
    : 100 / Math.max(count, 1);

  const seconds = (ms) => (ms / 1000).toFixed(1) + 's';

  const legend = '<div class="swim-legend">'
    + '<span class="swim-key normal"></span>节点'
    + '<span class="swim-key suspend"></span>等你作答'
    + '<span class="swim-key branch"></span>分支决策'
    + '<span class="swim-key error"></span>出错'
    + '<span class="muted">色块宽度 = 该节点耗时（不含你作答的等待时间）</span>'
    + '</div>';

  const body = rounds.map((r) => {
    const blocks = r.blocks.map((b) => {
      // 先看分支、再看状态：evaluate 的分支行 status 也是 ok，混在一起看不出哪条是决策
      const kind = b.branch ? 'branch' : (b.error ? 'error' : (b.suspend ? 'suspend' : 'normal'));
      const label = NODE_LABELS[b.node] || b.node;
      const title = b.node + ' · ' + (b.cost || 0) + ' ms · ' + (b.status || 'ok');
      return '<span class="swim-block ' + kind + '" style="flex:0 1 ' + widthOf(b.cost, r.blocks.length).toFixed(2)
           + '%" title="' + escapeHtml(title) + '">' + escapeHtml(label) + '</span>';
    }).join('');

    const branch = r.branch
      ? '<span class="swim-branch">→ ' + escapeHtml(branchLabel(r.branch)) + '</span>'
      : '<span class="swim-branch"></span>';

    return '<div class="swim-row">'
      + '<span class="swim-round">第 ' + (r.round || 1) + ' 轮</span>'
      + '<span class="swim-track">' + blocks + '</span>'
      + '<span class="swim-cost">' + seconds(r.total) + '</span>'
      + branch
      + '</div>';
  }).join('');

  return legend + body;
}