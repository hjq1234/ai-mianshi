package com.ke.nhservice.aimianshi.graph;

/**
 * 节点返回给引擎的指令，只有两种。
 * 用 sealed 限制住，将来加第三种时编译器会逼着所有 switch 补分支。
 */
public sealed interface NodeResult {

    /** 继续沿出边往下走 */
    record Next() implements NodeResult {}

    /** 挂起：保存现场后退出，不占用线程 */
    record Suspend() implements NodeResult {}

    NodeResult NEXT = new Next();
    NodeResult SUSPEND = new Suspend();
}