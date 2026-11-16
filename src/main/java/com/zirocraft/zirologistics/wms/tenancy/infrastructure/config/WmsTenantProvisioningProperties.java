package com.zirocraft.zirologistics.wms.tenancy.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wms.tenant.provisioning")
public class WmsTenantProvisioningProperties {
    private boolean enabled;
    private String tenantCode;
    private String legalName;
    private String displayName;
    private String timezone;
    private String currency;
    private String ownerEmail;
    private String ownerFullName;
    private String operationRef;
    private String operatorReference;
    private String reason;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String tenantCode) { this.tenantCode = tenantCode; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }
    public String getOwnerFullName() { return ownerFullName; }
    public void setOwnerFullName(String ownerFullName) { this.ownerFullName = ownerFullName; }
    public String getOperationRef() { return operationRef; }
    public void setOperationRef(String operationRef) { this.operationRef = operationRef; }
    public String getOperatorReference() { return operatorReference; }
    public void setOperatorReference(String operatorReference) { this.operatorReference = operatorReference; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
