package com.zirocraft.zirologistics.wms.authorization.application;

import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.WmsMembershipAccessProjection;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.WmsMembershipAccessRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves tenant scope and effective permissions from current persisted state.
 *
 * This service is intended to be called only after the user's credentials or
 * access token have been independently authenticated.
 */
@Service
@Profile("wms")
public class WmsMembershipAccessService {

    private final WmsMembershipAccessRepository accessRepository;

    public WmsMembershipAccessService(WmsMembershipAccessRepository accessRepository) {
        this.accessRepository = accessRepository;
    }

    @Transactional(readOnly = true)
    public WmsMembershipAccess resolve(String authenticatedUserPublicId, String selectedMembershipPublicId) {
        if (authenticatedUserPublicId == null || authenticatedUserPublicId.isBlank()
                || selectedMembershipPublicId == null || selectedMembershipPublicId.isBlank()) {
            throw new WmsMembershipAccessDeniedException();
        }

        List<WmsMembershipAccessProjection> rows = accessRepository.findActiveAccess(
                authenticatedUserPublicId, selectedMembershipPublicId);

        if (rows.isEmpty()) {
            throw new WmsMembershipAccessDeniedException();
        }

        WmsMembershipAccessProjection first = rows.getFirst();
        Set<String> permissions = new LinkedHashSet<>();
        for (WmsMembershipAccessProjection row : rows) {
            if (row.getPermissionCode() != null && !row.getPermissionCode().isBlank()) {
                permissions.add(row.getPermissionCode());
            }
        }

        return new WmsMembershipAccess(
                first.getUserId(),
                first.getUserPublicId(),
                first.getTenantId(),
                first.getTenantPublicId(),
                first.getMembershipId(),
                first.getMembershipPublicId(),
                permissions);
    }
}
