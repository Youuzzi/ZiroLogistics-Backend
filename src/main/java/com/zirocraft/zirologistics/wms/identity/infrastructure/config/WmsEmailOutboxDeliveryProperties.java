package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Required deployment configuration for encrypted identity-email delivery.
 * This bean is registered only when delivery is explicitly enabled.
 */
@Validated
@ConfigurationProperties(prefix = "wms.identity.email.delivery")
public class WmsEmailOutboxDeliveryProperties {

    @NotBlank private String smtpHost;
    @Min(1) private int smtpPort = 587;
    @NotBlank private String smtpUsername;
    @NotBlank private String smtpPassword;
    @NotBlank private String fromAddress;
    @NotBlank private String actionBaseUrl;
    @NotBlank private String invitationPath = "/accept-invitation";
    @NotBlank private String verificationPath = "/verify-email";
    @NotBlank private String passwordResetPath = "/reset-password";
    @Min(1000) private long pollIntervalMs = 5000;
    @Min(1) private int maxMessagesPerPoll = 10;
    @Min(1) private int maxAttempts = 8;
    @NotNull private Duration retryBaseDelay = Duration.ofSeconds(30);
    @NotNull private Duration retryMaxDelay = Duration.ofHours(1);
    @NotNull private Duration processingLease = Duration.ofMinutes(2);
    @Min(1000) private int smtpConnectionTimeoutMs = 5000;
    @Min(1000) private int smtpReadTimeoutMs = 10000;
    @Min(1000) private int smtpWriteTimeoutMs = 10000;

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String value) { smtpHost = value; }
    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int value) { smtpPort = value; }
    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String value) { smtpUsername = value; }
    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String value) { smtpPassword = value; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String value) { fromAddress = value; }
    public String getActionBaseUrl() { return actionBaseUrl; }
    public void setActionBaseUrl(String value) { actionBaseUrl = value; }
    public String getInvitationPath() { return invitationPath; }
    public void setInvitationPath(String value) { invitationPath = value; }
    public String getVerificationPath() { return verificationPath; }
    public void setVerificationPath(String value) { verificationPath = value; }
    public String getPasswordResetPath() { return passwordResetPath; }
    public void setPasswordResetPath(String value) { passwordResetPath = value; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long value) { pollIntervalMs = value; }
    public int getMaxMessagesPerPoll() { return maxMessagesPerPoll; }
    public void setMaxMessagesPerPoll(int value) { maxMessagesPerPoll = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration value) { retryBaseDelay = value; }
    public Duration getRetryMaxDelay() { return retryMaxDelay; }
    public void setRetryMaxDelay(Duration value) { retryMaxDelay = value; }
    public Duration getProcessingLease() { return processingLease; }
    public void setProcessingLease(Duration value) { processingLease = value; }
    public int getSmtpConnectionTimeoutMs() { return smtpConnectionTimeoutMs; }
    public void setSmtpConnectionTimeoutMs(int value) { smtpConnectionTimeoutMs = value; }
    public int getSmtpReadTimeoutMs() { return smtpReadTimeoutMs; }
    public void setSmtpReadTimeoutMs(int value) { smtpReadTimeoutMs = value; }
    public int getSmtpWriteTimeoutMs() { return smtpWriteTimeoutMs; }
    public void setSmtpWriteTimeoutMs(int value) { smtpWriteTimeoutMs = value; }
}
