package com.ke.nhservice.aimianshi.graph;

/** 图配置错误（编译期校验）或执行期错误（游标指向不存在的节点等） */
public class GraphException extends RuntimeException {

    public GraphException(String message) {
        super(message);
    }

    public GraphException(String message, Throwable cause) {
        super(message, cause);
    }
}