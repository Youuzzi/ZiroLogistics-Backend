package com.zirocraft.zirologistics.wms.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wms.bootstrap")
public class WmsBootstrapProperties {
    private boolean enabled;
    private String tenantCode;
    private String tenantLegalName;
    private String tenantDisplayName;
    private String timezone;
    private String currency;
    private String operatorEmail;
    private String operatorFullName;
    private String operatorPassword;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }
    public String getTenantLegalName() { return tenantLegalName; }
    public void setTenantLegalName(String tenantLegalName) { this.tenantLegalName = tenantLegalName; }
    public String getTenantDisplayName() { return tenantDisplayName; }
    public void setTenantDisplayName(String tenantDisplayName) { this.tenantDisplayName = tenantDisplayName; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getOperatorEmail() { return operatorEmail; }
    public void setOperatorEmail(String operatorEmail) { this.operatorEmail = operatorEmail; }
    public String getOperatorFullName() { return operatorFullName; }
    public void setOperatorFullName(String operatorFullName) { this.operatorFullName = operatorFullName; }
    public String getOperatorPassword() { return operatorPassword; }
    public void setOperatorPassword(String operatorPassword) { this.operatorPassword = operatorPassword; }
}
