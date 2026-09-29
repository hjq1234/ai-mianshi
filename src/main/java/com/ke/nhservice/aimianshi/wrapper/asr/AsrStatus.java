package com.ke.nhservice.aimianshi.wrapper.asr;

/** available=true 时 reason 为 null；native 库没加载上时 nativeVersion 为 null */
public record AsrStatus(boolean available, String reason, String nativeVersion, int maxSeconds) {
}