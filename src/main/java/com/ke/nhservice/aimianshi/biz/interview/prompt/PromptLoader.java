package com.ke.nhservice.aimianshi.biz.interview.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词外置在 resources/prompts/*.md，不硬编码进 Java 文件。
 * 提示词本质是配置，不是代码。
 *
 * 占位符用 {key} 而不是 String.format 的 %s——
 * 提示词里出现 % 是常事（「准确率提升 30%」），用 %s 会直接炸。
 */
@Component
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 读原始模板。缓存起来，避免每次出题都读一遍磁盘 */
    public String load(String name) {
        return cache.computeIfAbsent(name, key -> {
            ClassPathResource resource = new ClassPathResource("prompts/" + key + ".md");
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("提示词文件不存在或读取失败: prompts/" + key + ".md", e);
            }
        });
    }

    /** 读模板并把 {key} 替换掉。没传的占位符保持原样，方便发现漏传 */
    public String render(String name, Map<String, String> values) {
        String template = load(name);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return template;
    }
}