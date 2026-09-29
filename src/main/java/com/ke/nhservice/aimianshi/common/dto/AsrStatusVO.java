package com.ke.nhservice.aimianshi.common.dto;

import com.ke.nhservice.aimianshi.wrapper.asr.AsrStatus;

/**
 * wrapper 层有自己的类型（AsrStatus），响应体再包一层 VO —— 和
 * Resume（biz 层）→ ResumeVO（dto 层）是同一个形状，别让 controller 直接吐 wrapper 类型。
 *
 * 流式那两个字段**平铺**，不写成 stream: {available, reason} 嵌套对象。
 * 原因很具体：AsrApiCheck 里的 extract(json, key) 取的是**第一个**同名 key。
 * 嵌套对象里也有 available，一旦哪天字段顺序变了，extract(statusBody, "available")
 * 就会取到流式那个 —— 断言开始测另一个东西，而且**它是绿的**。平铺从根上避掉这种假绿。
 *
 * 离线那份的 maxSeconds 才有意义（录音上限）；流式那份没有「整段多长」的概念，
 * 它的长度由前端计时器管，所以流式传进来的 maxSeconds 是 0，前端不读它。
 */
public record AsrStatusVO(boolean available, String reason, String nativeVersion, int maxSeconds,
                          boolean streamAvailable, String streamReason) {

    public static AsrStatusVO of(AsrStatus offline, AsrStatus stream) {
        return new AsrStatusVO(offline.available(), offline.reason(), offline.nativeVersion(),
                offline.maxSeconds(), stream.available(), stream.reason());
    }
}