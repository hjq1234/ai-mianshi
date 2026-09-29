package com.ke.nhservice.aimianshi.common.dto;

import com.ke.nhservice.aimianshi.wrapper.asr.AsrStatus;

/**
 * wrapper 层有自己的类型（AsrStatus），响应体再包一层 VO —— 和
 * Resume（biz 层）→ ResumeVO（dto 层）是同一个形状，别让 controller 直接吐 wrapper 类型。
 */
public record AsrStatusVO(boolean available, String reason, String nativeVersion, int maxSeconds) {

    public static AsrStatusVO of(AsrStatus s) {
        return new AsrStatusVO(s.available(), s.reason(), s.nativeVersion(), s.maxSeconds());
    }
}