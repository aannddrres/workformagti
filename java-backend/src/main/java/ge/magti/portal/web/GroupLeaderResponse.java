package ge.magti.portal.web;

import ge.magti.portal.domain.User;

/** Mirrors schemas.py's GroupLeaderResponse (schemas.py:60-67). */
public record GroupLeaderResponse(Long id, String name) {
    public static GroupLeaderResponse from(User user) {
        return new GroupLeaderResponse(user.getId(), user.getName());
    }
}
