package com.ke.nhservice.aimianshi.graph;

/**
 * 执行现场：业务状态 + 引擎游标。
 * 两个字段分开存是因为它们的生命周期不同——state 归业务，cursor 归引擎。
 */
public class Execution<S> {

    private S state;
    private String cursor;

    public Execution() {
    }

    public Execution(S state, String cursor) {
        this.state = state;
        this.cursor = cursor;
    }

    public S getState() { return state; }

    public void setState(S state) { this.state = state; }

    public String getCursor() { return cursor; }

    public void setCursor(String cursor) { this.cursor = cursor; }
}