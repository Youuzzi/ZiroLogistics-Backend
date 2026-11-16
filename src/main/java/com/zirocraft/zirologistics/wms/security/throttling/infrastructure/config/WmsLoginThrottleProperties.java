package com.zirocraft.zirologistics.wms.security.throttling.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Required deployment policy and dedicated HMAC secret for WMS login throttling. */
@Validated
@ConfigurationProperties(prefix = "wms.security.throttle")
public class WmsLoginThrottleProperties {
    @NotBlank
    private String hmacKeyBase64;
    @Min(1)
    private int hmacKeyVersion;
    @NotNull
    private Duration sourceWindow;
    @Min(1)
    private int sourceMaximumRequests;
    @NotNull
    private Duration accountFailureWindow;
    @Min(1)
    private int accountFailureThreshold;
    @NotNull
    private Duration accountCooldown;
    private List<String> trustedProxyCidrs = new ArrayList<>();

    public String getHmacKeyBase64() { return hmacKeyBase64; }
    public void setHmacKeyBase64(String hmacKeyBase64) { this.hmacKeyBase64 = hmacKeyBase64; }
    public int getHmacKeyVersion() { return hmacKeyVersion; }
    public void setHmacKeyVersion(int hmacKeyVersion) { this.hmacKeyVersion = hmacKeyVersion; }
    public Duration getSourceWindow() { return sourceWindow; }
    public void setSourceWindow(Duration sourceWindow) { this.sourceWindow = sourceWindow; }
    public int getSourceMaximumRequests() { return sourceMaximumRequests; }
    public void setSourceMaximumRequests(int sourceMaximumRequests) { this.sourceMaximumRequests = sourceMaximumRequests; }
    public Duration getAccountFailureWindow() { return accountFailureWindow; }
    public void setAccountFailureWindow(Duration accountFailureWindow) { this.accountFailureWindow = accountFailureWindow; }
    public int getAccountFailureThreshold() { return accountFailureThreshold; }
    public void setAccountFailureThreshold(int accountFailureThreshold) { this.accountFailureThreshold = accountFailureThreshold; }
    public Duration getAccountCooldown() { return accountCooldown; }
    public void setAccountCooldown(Duration accountCooldown) { this.accountCooldown = accountCooldown; }
    public List<String> getTrustedProxyCidrs() { return List.copyOf(trustedProxyCidrs); }
    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null ? new ArrayList<>() : new ArrayList<>(trustedProxyCidrs);
    }
}
