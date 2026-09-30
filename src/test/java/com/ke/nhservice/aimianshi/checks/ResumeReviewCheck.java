package com.ke.nhservice.aimianshi.checks;

import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.biz.resume.ResumeReviewParser;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历改稿那条链上「写错了不报错、只是行为不对」的两件事（不是 JUnit，是能直接 main 跑的）。
 *
 * 1. **提示词占位符有没有漏传。** PromptLoader 对没传的 {key} 是**原样留着**的，漏了不报错——
 *    只是模型收到的 prompt 里带着一个字面量 "{resume}"，输出变差，
 *    而页面上一点异常都看不出来。这是这个程序存在的唯一理由。
 * 2. **产物解析。** 抽建议 / 去批注 / 分节只有一处实现，边界（没有 ## 标题、
 *    岗位节只剩批注行、批注在最前面）离线几条断言就能覆盖。
 *
 * 不起 Spring 容器、不调 LLM、不碰库。
 *
 * 跑法（在仓库根目录）：
 *   export JAVA_HOME="/c/Program Files/Java/jdk-21"
 *   ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q test-compile
 *   CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
 *   "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
 *     com.ke.nhservice.aimianshi.checks.ResumeReviewCheck
 *
 * 要 cp.txt（PromptLoader 用 spring-core 的 ClassPathResource、JsonUtil 用 Jackson 3），
 * 但**不起 Spring 容器**。
 */
public final class ResumeReviewCheck {

    private static int failed = 0;

    public static void main(String[] args) {
        checkPrompt();
        checkParser();
        checkTargetsJson();

        System.out.println();
        if (failed > 0) {
            System.out.println("FAIL —— " + failed + " 条没过");
            System.exit(1);
        }
        System.out.println("PASS —— 全部通过");
    }

    // ────────────────────────── 提示词 ──────────────────────────

    private static void checkPrompt() {
        System.out.println("【提示词占位符】");
        PromptLoader loader = new PromptLoader();
        String template = loader.load("resume_review");

        // 先钉模板本身：三个占位符一个都不能少（少一个就等于模型看不到那部分素材）
        for (String key : List.of("{targets}", "{interviewMaterial}", "{resume}")) {
            check("模板里有 " + key, template.contains(key));
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("targets", "① Java 后端\n   JD：（未提供，按该岗位通用标准）");
        vars.put("interviewMaterial", "（未提供）");
        vars.put("resume", "张三 · 3 年 Java 后端");
        String rendered = loader.render("resume_review", vars);

        // ★ 这条错了就是真出事了：带着 {resume} 这种字面量发给模型，页面上完全看不出来
        check("★ 三个占位符全被替换掉（渲染结果里不含任何 {）", !rendered.contains("{"));
        check("素材真的进去了", rendered.contains("张三 · 3 年 Java 后端"));
        check("没选面试记录时那句话也在", rendered.contains("（未提供）"));

        // 反证：漏传时占位符是会留着的。不做这一条，上面那条可能是恒真的
        Map<String, String> missing = new HashMap<>(vars);
        missing.remove("resume");
        check("反证：漏传 resume 时 {resume} 原样留着",
                loader.render("resume_review", missing).contains("{resume}"));
    }

    // ────────────────────────── 产物解析 ──────────────────────────

    private static final String SAMPLE = """
            > 建议：整份简历的动词太弱，把「参与」都换成做了什么。
            ## 个人信息
            > 建议：开头缺一句「几年经验 + 什么方向」，HR 三秒内看不到重点。
            张三 · 3 年 Java 后端
            ## 项目经历
            > 建议：订单中台那段写清楚你具体做了什么。
            ### 订单中台
            将下单链路的 3 次 RPC 合并为批量查询，P99 从【380ms】降到【120ms】。
            ## 这份简历最该先改的三件事
            补指标、项目往前放、删掉与岗位无关的技能。
            ## 投「Java 后端」要额外改什么
            > 建议：这个岗位强调交易链路，把订单中台那段往前放。
            ## 投「Go 后端」要额外改什么
            > 建议：Go 岗位看 channel 和调度，把并发调优那段提到显眼位置。
            """;

    private static void checkParser() {
        System.out.println();
        System.out.println("【产物解析】");
        List<ResumeReviewParser.Suggestion> s = ResumeReviewParser.suggestions(SAMPLE);

        check("★ 抽出 5 条建议（4 条在各节里 + 1 条在第一个标题之前）", s.size() == 5);
        check("第一个标题之前的批注归到「简历开头」",
                ResumeReviewParser.SECTION_HEAD.equals(s.get(0).section()));
        check("正文节的批注归到自己的章节", "个人信息".equals(s.get(1).section()));
        check("子标题（###）不改分组", "项目经历".equals(s.get(2).section()));
        check("岗位节的批注归到岗位节", "投「Go 后端」要额外改什么".equals(s.get(4).section()));
        check("★ 摘掉了「> 建议：」标记，只留正文",
                s.get(0).text().startsWith("整份简历的动词太弱"));
        check("反证：所有建议里都不含 > 或「建议：」",
                s.stream().noneMatch(x -> x.text().contains("建议：") || x.text().startsWith(">")));

        String doc = ResumeReviewParser.document(SAMPLE);
        check("★ 干净正文里一行批注都没有",
                doc.lines().noneMatch(l -> l.strip().startsWith(">")));
        check("★ 空掉的岗位节标题被去掉（不去的话参考稿里是一串空标题）",
                !doc.contains("投「Java 后端」要额外改什么")
                        && !doc.contains("投「Go 后端」要额外改什么"));
        check("有内容的小节标题都留着",
                doc.contains("## 个人信息") && doc.contains("## 项目经历")
                        && doc.contains("## 这份简历最该先改的三件事"));
        check("正文和子标题都在",
                doc.contains("张三 · 3 年 Java 后端") && doc.contains("### 订单中台")
                        && doc.contains("P99 从【380ms】降到【120ms】。"));
        check("没有连续空行", !doc.contains("\n\n\n"));
        check("首尾没有空行", doc.equals(doc.strip()));

        check("★ 空产物不炸", ResumeReviewParser.suggestions(null).isEmpty()
                && ResumeReviewParser.document(null).isEmpty()
                && ResumeReviewParser.count(null) == 0);

        String noHeading = "> 建议：A\n看看这里\n> 建议：B";
        List<ResumeReviewParser.Suggestion> flat = ResumeReviewParser.suggestions(noHeading);
        check("模型没写 ## 标题时全归「简历开头」（不报错，也不丢建议）",
                flat.size() == 2
                        && flat.stream().allMatch(x -> ResumeReviewParser.SECTION_HEAD.equals(x.section())));
        check("没标题时正文不被吃掉", ResumeReviewParser.document(noHeading).contains("看看这里"));
    }

    // ────────────────────────── targets_json ──────────────────────────

    private static void checkTargetsJson() {
        System.out.println();
        System.out.println("【targets_json 往返】");
        List<ResumeReviewTarget> targets = List.of(
                new ResumeReviewTarget("Java 后端", "负责交易链路高并发"),
                new ResumeReviewTarget("Go 后端", null));
        List<ResumeReviewTarget> back =
                ResumeReviewParser.parseTargets(ResumeReviewParser.writeTargets(targets));
        check("★ 写进去再读出来是同一份（JD 为 null 的那个也在）", targets.equals(back));
        check("空 json 读出来是空列表，不抛异常", ResumeReviewParser.parseTargets(null).isEmpty());
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label);
        if (!ok) {
            failed++;
        }
    }
}