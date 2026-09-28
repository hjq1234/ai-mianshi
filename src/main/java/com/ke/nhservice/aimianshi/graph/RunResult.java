package com.ke.nhservice.aimianshi.graph;

public record RunResult<S>(RunStatus status, String stoppedAt, S state, Throwable error) {

    public static <S> RunResult<S> finished(String at, S state) {
        return new RunResult<>(RunStatus.FINISHED, at, state, null);
    }

    public static <S> RunResult<S> suspended(String at, S state) {
        return new RunResult<>(RunStatus.SUSPENDED, at, state, null);
    }

    public static <S> RunResult<S> failed(String at, S state, Throwable error) {
        return new RunResult<>(RunStatus.FAILED, at, state, error);
    }

    public static <S> RunResult<S> stepLimit(String at, S state) {
        return new RunResult<>(RunStatus.STEP_LIMIT, at, state, null);
    }

    public boolean isFinished() {
        return status == RunStatus.FINISHED;
    }

    public boolean isSuspended() {
        return status == RunStatus.SUSPENDED;
    }
}