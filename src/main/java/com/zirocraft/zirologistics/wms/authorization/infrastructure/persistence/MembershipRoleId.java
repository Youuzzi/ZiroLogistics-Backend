package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class MembershipRoleId implements Serializable {
    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;
}
