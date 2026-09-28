package com.ke.nhservice.aimianshi.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
public class AppProperties {

    /** HMAC 签名密钥。服务重启后 token 仍有效，靠的就是它不变 */
    private String secret = "change-me-before-deploy-please";

    private int ttlHours = 72;

    public String getSecret() { return secret; }

    public void setSecret(String secret) { this.secret = secret; }

    public int getTtlHours() { return ttlHours; }

    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }

    public long getTtlMillis() {
        return ttlHours * 3600_000L;
    }
}