package com.fsp.core.orgunit;

import java.util.List;
import java.util.UUID;

/** Wire records for branches and departments (OrgUnitController). */
public final class OrgUnitDtos {

    private OrgUnitDtos() {}

    public record OrgUnitView(UUID id, String code, String name, String status) {}

    /** One page of an organization's branches or departments (AGENTS §2f). */
    public record OrgUnitPage(List<OrgUnitView> items, int page, int size, long totalElements, int totalPages,
                              boolean hasNext, String sort) {}
}
