package com.ke.nhservice.aimianshi.common.auth;

import com.ke.nhservice.aimianshi.common.config.AppProperties;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC 自签 token，零依赖（JDK 自带的 javax.crypto）。
 *
 * 格式：base64url( userId + "." + expireAt + "." + hmacSha256(userId + "." + expireAt) )
 *
 * 已知限制：无法主动失效，登出只是前端删掉 token。单用户自用场景可接受。
 * 好处是密钥在配置里，服务重启后 token 依然有效。
 */
@Component
public class TokenUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AppProperties props;

    public TokenUtil(AppProperties props) {
        this.props = props;
    }

    public String issue(Long userId) {
        long expireAt = System.currentTimeMillis() + props.getTtlMillis();
        String payload = userId + "." + expireAt;
        String raw = payload + "." + sign(payload);
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 用户 id；token 缺失/被篡改/过期一律抛 401 */
    public Long verify(String token) {
        if (token == null || token.isBlank()) {
            throw BizException.unauthorized("未登录");
        }
        String raw;
        try {
            raw = new String(DECODER.decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw BizException.unauthorized("登录凭证格式错误");
        }

        String[] parts = raw.split("\\.");
        if (parts.length != 3) {
            throw BizException.unauthorized("登录凭证格式错误");
        }

        String expected = sign(parts[0] + "." + parts[1]);
        // 定长比较，避免时序侧信道
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw BizException.unauthorized("登录凭证校验失败");
        }

        long expireAt;
        try {
            expireAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw BizException.unauthorized("登录凭证格式错误");
        }
        if (System.currentTimeMillis() > expireAt) {
            throw BizException.unauthorized("登录已过期，请重新登录");
        }

        return Long.parseLong(parts[0]);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(props.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }
}