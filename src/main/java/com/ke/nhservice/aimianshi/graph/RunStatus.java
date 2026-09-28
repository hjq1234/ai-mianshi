package com.ke.nhservice.aimianshi.graph;

public enum RunStatus {

    /** 跑到结束节点，正常收尾 */
    FINISHED,

    /** 有节点挂了 Suspend，现场已保存在 Execution 里，调用方负责落库 */
    SUSPENDED,

    /** 节点抛异常。游标停在出错的那个节点上，修好后可以原地重跑 */
    FAILED,

    /** 步数跑满仍未到终点，疑似死循环。不抛异常，交给调用方判断 */
    STEP_LIMIT
}