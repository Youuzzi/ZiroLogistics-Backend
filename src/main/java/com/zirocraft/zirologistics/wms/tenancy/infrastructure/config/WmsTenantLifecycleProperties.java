package com.zirocraft.zirologistics.wms.tenancy.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wms.tenant.lifecycle")
public class WmsTenantLifecycleProperties {
    private boolean enabled;
    private String action;
    private String tenantPublicId;
    private String reason;
    private String operationRef;
    private String confirmation;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTenantPublicId() { return tenantPublicId; }
    public void setTenantPublicId(String tenantPublicId) { this.tenantPublicId = tenantPublicId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOperationRef() { return operationRef; }
    public void setOperationRef(String operationRef) { this.operationRef = operationRef; }
    public String getConfirmation() { return confirmation; }
    public void setConfirmation(String confirmation) { this.confirmation = confirmation; }
}
