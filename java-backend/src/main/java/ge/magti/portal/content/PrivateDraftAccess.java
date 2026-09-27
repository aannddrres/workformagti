package ge.magti.portal.content;

import ge.magti.portal.domain.User;

/** Ownership boundary shared by reads, editor commands and the trash lifecycle. */
public final class PrivateDraftAccess {
    private PrivateDraftAccess() {
    }

    public static boolean canAccess(boolean privateDraft, Long authorId, User actor) {
        return !privateDraft || (authorId != null && actor != null && authorId.equals(actor.getId()));
    }
}
