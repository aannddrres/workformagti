package ge.magti.portal.web;

import java.util.List;

/** Read-only directory-owned organisation tree. */
public record OrgStructureResponse(List<OrgDepartmentResponse> departments) {
}
