package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * PO-40: who a mandatory item binds now, and who it will bind when a
 * scheduled article is published. The editor's form reads it before saving,
 * so that a change taking the item out of someone's reach says so first.
 *
 * <p>Split the way the read receipts are: {@code departments} is an aggregate
 * every {@code compliance.assign} holder may see, and is what the form
 * counts from; {@code addressees} names people only within the caller's own
 * leadership scope, and everyone for a system administrator.
 */
public record MandatoryAddresseesResponse(
        @JsonProperty("in_force_total") int inForceTotal,
        @JsonProperty("pending_total") int pendingTotal,
        List<DepartmentRow> departments,
        List<Entry> addressees
) {
    public static MandatoryAddresseesResponse empty() {
        return new MandatoryAddresseesResponse(0, 0, List.of(), List.of());
    }

    /**
     * One department's share: bound now, bound at publication, and how many
     * of either have already confirmed.
     */
    public record DepartmentRow(
            String department,
            @JsonProperty("in_force") int inForce,
            int pending,
            int read
    ) {
    }

    /**
     * @param read    has already confirmed this item; the confirmation stays
     *                evidence whatever happens to the obligation
     * @param pending bound only once the article is published
     */
    public record Entry(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("user_name") String userName,
            String department,
            boolean read,
            boolean pending
    ) {
    }
}
