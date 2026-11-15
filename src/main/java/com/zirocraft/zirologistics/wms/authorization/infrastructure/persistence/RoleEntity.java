package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.authorization.domain.RoleStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "roles")
public class RoleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 36, columnDefinition = "char(36)", updatable = false)
    private String publicId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "role_code", nullable = false, length = 80)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 120)
    private String roleName;

    @Column(length = 255)
    private String description;

    @Column(name = "is_system_role", nullable = false)
    private boolean systemRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoleStatus status = RoleStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version @Column(nullable = false)
    private Long version;

    @PrePersist
    void assignPublicId() {
        if (publicId == null || publicId.isBlank()) publicId = UUID.randomUUID().toString();
    }
}
