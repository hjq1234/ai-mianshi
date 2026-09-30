# 面试记录软删除设计

## 要解决的事

「面试记录」页只能看，不能删。调模型、调提示词、试语音，一路跑下来攒了一堆没用的场次
（库里现在 6 条，一半是测试跑出来的、一题没答完的），列表越翻越长，真正想看的那场被压在下面。

加一个删除按钮。

## 两个已经定下来的决定

| | 选的 | 另一条为什么没选 |
|---|---|---|
| **谁能删** | 进行中的也能删 | 只能删已结束的话，半途放弃的（点开发现不想答了）永远清不掉，正是最该清的那类 |
| **删的语义** | **软删**（标记隐藏，数据留着） | 真删会把 `/stats` 的历史均分一起改掉。「删掉一场答砸的面试」不该顺手把「历史水平（N 场）」也改了 |

### 软删的代价，以及为什么这个代价可控

代价是：**每一条读 `t_interview_record` 的查询都得带过滤，漏一处就是「删了还在」**——
而且漏了不报错，只是那一处还看得见已删的数据，很难发现。

所以这一节先把「要过滤的地方」穷举出来。`grep -rn "t_interview_record" src/main` 的结果是：
读操作**只有 4 处**，全在 `InterviewDao`，而且全都有现成的 `WHERE`，加一个 `AND` 就行。

| `InterviewDao` | 行 | 现在的 WHERE | 加上 |
|---|---|---|---|
| `findRecord` | 114 | `WHERE r.id = ?` | `AND r.deleted = 0` |
| `listByUser` | 136 | `WHERE r.user_id = ?` | `AND r.deleted = 0` |
| `countByUser` | 144 | `WHERE user_id = ?` | `AND deleted = 0` |
| `listFinishedIdsWithAnswers` | 217 | `WHERE r.user_id = ? AND r.status = 'finished'` | `AND r.deleted = 0` |

（`insertRecord` / `saveState` / `finishRecord` 是写，不用过滤；两个 `UPDATE` 也够不着已删的记录，
因为所有写路径都先过 `findRecord`，见下。）

### `findRecord` 是咽喉，所以子表不用动

`t_interview_dialogue` 和 `t_graph_trace` 上**一处都不用改**。理由：

- 它们的查询（`listDialogues` / `listTraces` / `countDialoguesByRecord` / `listDialoguesByRecords`）
  收的都是 **record id**，而那些 id 全都出自上面那 4 个查询之一
  （列表页：`listByUser` + `countDialoguesByRecord`；复盘页：`findRecord` → `listDialogues`；`/stats`：`listFinishedIdsWithAnswers`）
- 所以 record 这一层挡住了，子表跟着就查不出来

`findRecord` 另外还是**所有写路径的唯一入口**：`answer` / `resume` / `state` / `finish`
全都先走 `InterviewEngine.loadOwned` → `findRecord`。所以过滤了它，
对一条已删的记录调这些接口会直接 `BizException.notFound`（404），不用在每个写方法上再拦一次。

## 数据层

### 加列

`schema.sql` 的 `CREATE TABLE` 里加一行：

```sql
    deleted     INTEGER NOT NULL DEFAULT 0,  -- 软删标记。1 = 已删，查询一律带 AND deleted = 0
```

**但这一行只对新库生效。** `CREATE TABLE IF NOT EXISTS` 对已经存在的表是整条跳过的，
所以 `data/interview.db`（你现在那 6 条记录）不会被它改到。

### 迁移：`SqliteInitializer` 里自动升

`SqliteInitializer` 的定位本来就是「启动时执行一次的事情」——它现在在那儿设 `journal_mode=WAL`。
加一段：

```
PRAGMA table_info(t_interview_record)
  → 结果里没有 deleted 这一列？
      ALTER TABLE t_interview_record ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0
  → 有？
      什么也不做
```

**为什么自动做，而不是写进 README 让人手工跑一次**：手工那条路在单机上也能用，
但它的失败方式是「忘了 → 每个面试接口都 500 报 `no such column: deleted`」。
这跟语音模型没下（软失败，只是麦克风不显示）不是一个量级，而且**没有任何地方会提醒你**。
6 行幂等代码换掉这一整类问题，划算。

用 `PRAGMA table_info` 判断而不是 `try { ALTER } catch {}`：后者的控制流是异常，
而且会连「真的执行失败」一起吞掉。

### `softDelete`

```java
/** 软删。数据留着，只是所有查询都看不见了（见类注释里那 4 处过滤） */
public void softDelete(Long id) {
    jdbc.update("UPDATE t_interview_record SET deleted = 1, updated_at = ? WHERE id = ?",
            System.currentTimeMillis(), id);
}
```

单条 UPDATE，不需要事务。（`ResumeDao:79` 那个 `@Transactional` 是因为它一次改两行，
这里改一行。）

## 业务层

`InterviewEngine` 里加一个方法。放这儿而不是新建 `InterviewService`：这个类已经是事实上的
面试 service 了（`start` / `answer` / `finish` / `resume` / `state` 和私有的 `loadOwned` 都在里面），
再为一行删除开一个类不值得。

```java
public void delete(Long userId, Long recordId) {
    // 复用已有的 requireRecord（里面就是 loadOwned），不自己写一遍归属校验：
    // 「不存在」和「不是你的」在 loadOwned 里都统一抛 notFound（面试记录不存在），
    // 所以已删的记录在这里自动也是 404 —— 对同一条再删一次会 404，是正确行为不是 bug
    requireRecord(userId, recordId);
    dao.softDelete(recordId);
    log.info("删除面试记录 | 面试={} 用户={}", recordId, userId);
}
```

`requireRecord`（`InterviewEngine:164`）是现成的 public 入口，注释写着「归属校验统一走 `loadOwned`，
不在这里重复写一遍」——删除正好是第三个调用方。它顺带会把 `state_json` 反序列化出来（删一条记录用不到），
但为省这点开销另写一份归属判断，正是那句注释在防的事。

## 接口

```java
@DeleteMapping("/{id}")
public ApiResponse<Void> delete(@PathVariable Long id) {
    engine.delete(UserContext.get(), id);
    return ApiResponse.ok();
}
```

和简历那条（`ResumeController:49`）一一对应，不新增返回体。

## 前端

`history.html` 最后那个 `<td class="right">` 里，在「查看复盘 / 继续面试」后面补一个
（**两个状态都有**，和上面「进行中的也能删」对应）：

```html
<button class="small danger" @click="remove(r.id)">删除</button>
```

`methods` 里加：

```js
async remove(id) {
  if (!confirm('确定删除这条面试记录？')) return;
  try {
    await api('/api/interview/' + id, { method: 'DELETE' });
    // 从本地列表摘掉那一行，不整页刷新：刷新会把滚动位置也弄丢
    this.list = this.list.filter(r => r.id !== id);
    toast('已删除');
  } catch (e) {
    toast(e.message, true);
  }
}
```

`danger` 这个 class 简历页已经在用，CSS 里有了，不用新增样式。

## 明确不做的

- **不做恢复 / 回收站**。要找回就直接改库 `UPDATE ... SET deleted = 0`——单用户自用，
  为这个做界面不划算。真删也一样：`DELETE FROM` 三张表，需要时手工跑。
- **不做批量删除**。列表本来就不长，多选框 + 全选是一整套 UI。
- **不动子表**。软删的意义就是数据都留着；而且如上所述，子表靠 record 那层挡住就够。
- **不改 `/stats` 的语义**。软删之后它天然不受影响——这正是选软删的原因。

## 怎么验

1. 删一条**进行中**的 → 列表里没了
2. `/stats` 的「历史均分（N 场）」**不变** ← 软删的全部意义在这一条
3. 直接敲 `report.html?id=<已删的>` → 提示「面试记录不存在」，不是打开一个幽灵页
4. 重启服务 → 删掉的还是不在（说明迁移生效，而且没把老数据弄丢）
5. 对同一条再点一次删除（或直接调接口）→ 404，不是 500