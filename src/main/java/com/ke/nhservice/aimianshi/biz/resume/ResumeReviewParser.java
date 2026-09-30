package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 产物的解析：一份 Markdown → 建议列表 / 干净正文 / 建议条数。
 *
 * ★ 这是「怎么从产物里抽建议」的**唯一实现处**。页面上那两个视图（建议面板、参考稿）
 *   都由它派生，Java 和 JS 不各写一份——同理 InterviewStats 一个人管着「null 维度怎么算」。
 *
 * 纯函数：不碰库、不碰 LLM，所以分行、空节、没有标题这些边界离线就能验。
 */
public final class ResumeReviewParser {

    /** 批注行的标记。提示词里就是这么约定的 */
    private static final String QUOTE = ">";

    /** 批注正文的前缀，只是给机器认的标记，展示时要摘掉 */
    private static final String PREFIX = "建议：";

    /** 第一个 ## 之前的批注归到这一组 */
    public static final String SECTION_HEAD = "简历开头";

    private ResumeReviewParser() {
    }

    /** 一条建议：它属于哪一节 + 正文 */
    public record Suggestion(String section, String text) {
    }

    /**
     * targets_json 的载荷。包一层是为了复用 JsonUtil.fromJson(String, Class)——
     * 直接反序列化 List&lt;ResumeReviewTarget&gt; 要 TypeReference，不值得为这一个字段加一套 API。
     * （InterviewDao 里的 EvalJson 是同一个写法，那个也是包在 JsonUtil 里读的。）
     */
    record TargetList(List<ResumeReviewTarget> items) {
    }

    /** 所有以 > 开头的行。section 是它上面最近的那个 ## 标题 */
    public static List<Suggestion> suggestions(String markdown) {
        return scan(markdown).suggestions();
    }

    /** 建议条数。写入时算一次存进 suggestion_count，列表页就不用拖全文了 */
    public static int count(String markdown) {
        return suggestions(markdown).size();
    }

    /**
     * 去掉批注行之后的干净正文，给页面渲染参考稿用。
     *
     * 两步不只是「少几行」：
     *  1. 岗位节和「最该先改的三件事」这些节的批注全被抽走了，标题会剩下一个空壳
     *  2. 批注被抽走之后会留下成片的空行
     */
    public static String document(String markdown) {
        List<Line> body = scan(markdown).body();
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            Line line = body.get(i);
            // ★ 空掉的小节标题也去掉。不去的话参考稿里会出现
            //   「## 投「Go 后端」要额外改什么」下面空无一物——按提示词约定，
            //   岗位节里只该有 > 建议： 行，抽掉之后确实什么都不剩
            if (line.sectionHead() && emptyUntilNextHead(body, i + 1)) {
                continue;
            }
            kept.add(line.text());
        }
        return squeeze(kept);
    }

    // ────────────────────────── targets_json ──────────────────────────

    public static String writeTargets(List<ResumeReviewTarget> targets) {
        return JsonUtil.toJson(new TargetList(targets == null ? List.of() : targets));
    }

    public static List<ResumeReviewTarget> parseTargets(String json) {
        if (json == null || json.isBlank()) {
            // 老数据或写失败时为 null，不能让它把整个列表页带崩
            return List.of();
        }
        List<ResumeReviewTarget> items = JsonUtil.fromJson(json, TargetList.class).items();
        return items == null ? List.of() : items;
    }

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 走一遍拿到两样东西：建议列表 + 去掉批注行之后的正文行。
     * 走一遍而不是两遍，是因为「这一行属于哪一节」两边都要知道。
     */
    private static Scan scan(String markdown) {
        List<Suggestion> suggestions = new ArrayList<>();
        List<Line> body = new ArrayList<>();
        String section = SECTION_HEAD;

        for (String raw : lines(markdown)) {
            String line = raw.strip();
            if (isHeading(line)) {
                // 只把 ## 当分节边界。### 之类照收进正文，但不改 section——
                // 简历里 ### 是项目名，拿它当分组名会把「项目经历那几条」拆成一堆单条
                boolean sectionHead = line.startsWith("##") && !line.startsWith("###");
                if (sectionHead) {
                    section = line.replaceFirst("^#+\\s*", "").strip();
                }
                body.add(new Line(line, sectionHead));
            } else if (line.startsWith(QUOTE)) {
                String text = line.substring(QUOTE.length()).strip();
                if (text.startsWith(PREFIX)) {
                    text = text.substring(PREFIX.length()).strip();
                }
                if (!text.isEmpty()) {
                    suggestions.add(new Suggestion(section, text));
                }
            } else {
                // 正文保留行首缩进（列表项的缩进有用），只清掉行尾空白
                body.add(new Line(raw.stripTrailing(), false));
            }
        }
        return new Scan(suggestions, body);
    }

    /** 从 from 往后直到下一个分节标题，中间是不是全是空行（是 → 这一节是空的） */
    private static boolean emptyUntilNextHead(List<Line> body, int from) {
        for (int i = from; i < body.size(); i++) {
            if (body.get(i).sectionHead()) {
                return true;
            }
            if (!body.get(i).text().isBlank()) {
                return false;
            }
        }
        // 到结尾都没撞上下一个标题：这一节就是空的
        return true;
    }

    /** 连续空行压成一个、去掉首尾空行。批注抽走之后会留下成片的空行 */
    private static String squeeze(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                if (out.isEmpty() || out.get(out.size() - 1).isEmpty()) {
                    continue;
                }
                out.add("");
            } else {
                out.add(line);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return String.join("\n", out);
    }

    /**
     * 行首有几个 # 就算标题。
     *
     * 故意不按 CommonMark 要求「# 后面必须有空格」：这里要认的是**模型的意图**，
     * 它少写一个空格不该让整节的建议掉进「简历开头」那一组。
     */
    private static boolean isHeading(String line) {
        return line.startsWith("#");
    }

    private static List<String> lines(String markdown) {
        return markdown == null ? List.of() : List.of(markdown.split("\r?\n", -1));
    }

    private record Line(String text, boolean sectionHead) {
    }

    private record Scan(List<Suggestion> suggestions, List<Line> body) {
    }
}