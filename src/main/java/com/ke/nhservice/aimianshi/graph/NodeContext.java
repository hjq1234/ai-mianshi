package com.ke.nhservice.aimianshi.graph;

import java.util.HashMap;
import java.util.Map;

/**
 * 执行期上下文：携带节点需要的服务依赖（LLM 客户端、DAO、提示词加载器…）。
 *
 * 引擎不允许认识任何业务类型，所以这里不写具体字段，改用「类型 -> 实例」注册表，
 * 节点自己按类型取。这样 graph 包可以完全脱离 biz / wrapper 编译。
 */
public class NodeContext {

    private final Map<Class<?>, Object> components = new HashMap<>();

    public <T> NodeContext put(Class<T> type, T instance) {
        components.put(type, instance);
        return this;
    }

    /** type.cast 而不是强制转型，避免 @SuppressWarnings 满天飞 */
    public <T> T get(Class<T> type) {
        Object value = components.get(type);
        if (value == null) {
            throw new GraphException("NodeContext 中未注册组件: " + type.getName());
        }
        return type.cast(value);
    }
}