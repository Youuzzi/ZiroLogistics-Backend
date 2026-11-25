package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnProperty(
        prefix = "wms.identity.email.delivery",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(WmsEmailOutboxDeliveryProperties.class)
@EnableScheduling
public class WmsEmailOutboxDeliveryConfiguration {

    @Bean
    JavaMailSender wmsIdentityEmailJavaMailSender(WmsEmailOutboxDeliveryProperties properties) {
        validateConfiguration(properties);

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getSmtpHost().trim());
        sender.setPort(properties.getSmtpPort());
        sender.setUsername(properties.getSmtpUsername());
        sender.setPassword(properties.getSmtpPassword());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding("UTF-8");

        Properties mail = sender.getJavaMailProperties();
        mail.put("mail.smtp.auth", "true");
        mail.put("mail.smtp.starttls.enable", "true");
        mail.put("mail.smtp.starttls.required", "true");
        mail.put("mail.smtp.ssl.checkserveridentity", "true");
        mail.put("mail.smtp.connectiontimeout", properties.getSmtpConnectionTimeoutMs());
        mail.put("mail.smtp.timeout", properties.getSmtpReadTimeoutMs());
        mail.put("mail.smtp.writetimeout", properties.getSmtpWriteTimeoutMs());
        mail.put("mail.debug", "false");
        return sender;
    }

    static void validateConfiguration(WmsEmailOutboxDeliveryProperties properties) {
        int maximumSmtpTimeoutMs = Math.max(properties.getSmtpConnectionTimeoutMs(),
                Math.max(properties.getSmtpReadTimeoutMs(), properties.getSmtpWriteTimeoutMs()));
        if (properties.getRetryBaseDelay().isNegative() || properties.getRetryBaseDelay().isZero()
                || properties.getRetryMaxDelay().compareTo(properties.getRetryBaseDelay()) < 0
                || properties.getProcessingLease().isNegative() || properties.getProcessingLease().isZero()
                || properties.getProcessingLease().compareTo(java.time.Duration.ofMillis(maximumSmtpTimeoutMs)) <= 0
                || properties.getMaxMessagesPerPoll() > 100
                || properties.getMaxAttempts() > 100) {
            throw new IllegalStateException("WMS identity email delivery policy is invalid");
        }
        try {
            InternetAddress address = new InternetAddress(properties.getFromAddress(), true);
            address.validate();
        } catch (AddressException invalidAddress) {
            throw new IllegalStateException("WMS identity email sender address is invalid");
        }

        URI base;
        try {
            base = URI.create(properties.getActionBaseUrl());
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalStateException("WMS identity email action base URL is invalid");
        }
        boolean localHttp = "http".equalsIgnoreCase(base.getScheme())
                && ("localhost".equalsIgnoreCase(base.getHost()) || "127.0.0.1".equals(base.getHost()));
        if ((!"https".equalsIgnoreCase(base.getScheme()) && !localHttp)
                || base.getHost() == null || base.getRawQuery() != null || base.getRawFragment() != null
                || base.getUserInfo() != null) {
            throw new IllegalStateException("WMS identity email action base URL must be HTTPS");
        }
        validatePath(properties.getInvitationPath());
        validatePath(properties.getVerificationPath());
        validatePath(properties.getPasswordResetPath());
    }

    private static void validatePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("?") || path.contains("#") || path.contains("..")) {
            throw new IllegalStateException("WMS identity email action path is invalid");
        }
    }
}
