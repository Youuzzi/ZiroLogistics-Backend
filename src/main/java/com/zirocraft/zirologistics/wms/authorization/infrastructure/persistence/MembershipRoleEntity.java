package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "membership_roles")
public class MembershipRoleEntity {
    @EmbeddedId
    private MembershipRoleId id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
