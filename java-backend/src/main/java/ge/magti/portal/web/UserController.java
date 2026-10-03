package ge.magti.portal.web;

import ge.magti.portal.announcement.BroadcastAuthorizationService;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.compliance.ComplianceProgressQueryService;
import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.time.OffsetDateTime;

/**
 * Mirrors routers/users.py's 14 endpoints, minus one: {@code POST
 * /api/users/{user_id}/nudge} (routers/users.py:365-390) is deliberately
 * not ported. It has no durable side effect at all in Python -- it's a
 * pure SSE publish with no DB write, unlike everything else deferred so
 * far in this port (which all kept a durable half). Presented to the user
 * concretely: the alternative was faking success with no real delivery, or
 * silently rerouting it through the legacy messages table (a real behavior
 * change, not a straight port). User chose to defer
 * it outright, same rationale as {@code GET /api/stream}: it rides the exact
 * same SSE broker.
 * Today the frontend's nudge button will get a 404 (a real failure, shown
 * to the manager), not a misleading fake success.
 *
 * <p>Python's file-level comment flags a Starlette route-registration-order
 * trap between {@code PUT /api/users/me} and {@code PUT /api/users/{user_id}}
 * (routers/users.py:5-10). Spring MVC dispatches by most-specific pattern
 * match, not registration order, so that trap doesn't exist here -- the two
 * methods below can be declared in any order.
 */
@RestController
public class UserController {

    private final UserRepository userRepository;
    private final ComplianceProgressQueryService complianceProgressQueryService;
    private final PasswordEncoder passwordEncoder;
    private final PermissionChecker permissionChecker;
    private final UserPermissionOverrideRepository permissionOverrideRepository;
    private final BroadcastAuthorizationService broadcastAuthorizationService;
    private final MutationAuditService mutationAuditService;
    private final UserDirectoryQueryService userDirectoryQueryService;
    private final OrgDirectoryQueryService orgDirectoryQueryService;
    private final PortalProperties properties;

    public UserController(
            UserRepository userRepository,
            ComplianceProgressQueryService complianceProgressQueryService,
            PasswordEncoder passwordEncoder, PermissionChecker permissionChecker,
            UserPermissionOverrideRepository permissionOverrideRepository,
            BroadcastAuthorizationService broadcastAuthorizationService,
            MutationAuditService mutationAuditService,
            UserDirectoryQueryService userDirectoryQueryService,
            OrgDirectoryQueryService orgDirectoryQueryService,
            PortalProperties properties) {
        this.userRepository = userRepository;
        this.complianceProgressQueryService = complianceProgressQueryService;
        this.passwordEncoder = passwordEncoder;
        this.permissionChecker = permissionChecker;
        this.permissionOverrideRepository = permissionOverrideRepository;
        this.broadcastAuthorizationService = broadcastAuthorizationService;
        this.mutationAuditService = mutationAuditService;
        this.userDirectoryQueryService = userDirectoryQueryService;
        this.orgDirectoryQueryService = orgDirectoryQueryService;
        this.properties = properties;
    }

    /**
     * The owner decided on 2026-09-21 that roles come from the company
     * directory on every sign-in. A role typed in here would therefore last
     * only until that person's next sign-in and then silently revert -- an
     * administrator would believe they had promoted someone who, an hour
     * later, is an operator again. Refusing is the honest answer.
     */
    static final String ROLE_MANAGED_BY_DIRECTORY_DETAIL =
            "როლს კომპანიის სისტემა მართავს და ის აქ ვერ შეიცვლება. მიმართეთ IT-ს.";

    private boolean rolesManagedByDirectory() {
        return properties.getSecurity().getCorporate().rolesManagedByDirectory();
    }

    /** Port of read_users_me (routers/users.py:29-50). */
    @GetMapping("/api/users/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        boolean canViewAuditLog = user.getRole() == Role.SYSTEM_ADMIN;
        // The permission list has to be the one the gates use. Since the
        // Phase 6 cutover users.permissions decides nothing, so shipping it
        // here would show the account page a set of abilities that no longer
        // matches what the caller can actually do.
        return ResponseEntity.ok(CurrentUserResponse.from(
                user, canViewAuditLog, permissionChecker.effectivePermissions(user), rolesManagedByDirectory()));
    }

    /** The authenticated caller's effective capabilities for client-side access decisions. */
    @GetMapping("/api/me/effective-access")
    public ResponseEntity<?> getEffectiveAccess(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(EffectiveAccessResponse.from(
                user, permissionChecker.effectivePermissions(user), broadcastAuthorizationService.canPublish(user)));
    }

    /** Port of update_users_me (routers/users.py:53-69). */
    @PutMapping("/api/users/me")
    @Transactional
    public ResponseEntity<?> updateCurrentUser(
            @Valid @RequestBody UserSelfUpdateRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        if (request.name() != null && !request.name().equals(user.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "detail", "სახელი იმართება კომპანიის კატალოგიდან და პორტალიდან არ იცვლება."));
        }
        Map<String, Object> before = MutationAuditService.userSnapshot(user);
        if (request.position() != null) {
            user.setPosition(request.position());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.cardStyle() != null) {
            user.setCardStyle(request.cardStyle());
        }
        User saved = userRepository.saveAndFlush(user);
        mutationAuditService.recordSuccess(
                user,
                "UPDATE_USER_PROFILE",
                "user",
                saved.getId(),
                saved.getName(),
                before,
                MutationAuditService.userSnapshot(saved));
        return ResponseEntity.ok(UserResponse.from(saved, permissionOverrideRepository.findByUserId(saved.getId())));
    }

    /** Port of change_own_password (routers/users.py:73-97). */
    @PostMapping("/api/users/me/password")
    @Transactional
    public ResponseEntity<?> changeOwnPassword(
            @Valid @RequestBody PasswordChangeRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "detail", "პაროლი იმართება კომპანიის Active Directory-ში და პორტალიდან არ იცვლება."));
    }

    /** Port of bulk_reassign_roles (routers/users.py:101-184). */
    @PostMapping("/api/admin/roles/bulk-reassign")
    @Transactional
    public ResponseEntity<?> bulkReassignRoles(
            @Valid @RequestBody BulkRoleReassignRequest request, @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        if (rolesManagedByDirectory()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", ROLE_MANAGED_BY_DIRECTORY_DETAIL));
        }
        Role newRole;
        try {
            newRole = Role.fromValue(request.newRole());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", "უცნობი როლი"));
        }

        Set<Long> targetIds = new LinkedHashSet<>();
        for (Long id : request.userIds()) {
            if (!id.equals(admin.getId())) {
                targetIds.add(id);
            }
        }
        if (targetIds.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "detail", "არცერთი მომხმარებელი არ არის შესარჩევი (საკუთარი როლის შეცვლა ჯგუფურად შეუძლებელია)."));
        }

        List<User> users = userRepository.findAllById(targetIds);
        if (users.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "მომხმარებლები ვერ მოიძებნა"));
        }

        // Kept as a set-wide check rather than routed through
        // refuseIfLastSystemAdmin: demoting several admins at once is only
        // safe if an admin survives ALL of them, which a per-user check
        // cannot see. Same rule, same message, different arithmetic.
        if (newRole != Role.SYSTEM_ADMIN) {
            List<Long> demotedAdminIds = users.stream()
                    .filter(u -> u.getRole() == Role.SYSTEM_ADMIN)
                    .map(User::getId)
                    .toList();
            if (!demotedAdminIds.isEmpty()) {
                long remaining = userRepository.countByRoleAndActiveTrueAndIdNotIn(Role.SYSTEM_ADMIN, demotedAdminIds);
                if (remaining == 0) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("detail", "ბოლო სისტემური ადმინისტრატორის როლის შეცვლა შეუძლებელია."));
                }
            }
        }

        Map<Long, Map<String, Object>> beforeByUser = new HashMap<>();
        Map<Long, String> previousRoleByUser = new HashMap<>();
        List<User> changedUsers = new ArrayList<>();
        for (User u : users) {
            if (u.getRole() == newRole) {
                continue;
            }
            beforeByUser.put(u.getId(), MutationAuditService.userSnapshot(u));
            String oldRoleValue = u.getRole().value();
            previousRoleByUser.put(u.getId(), oldRoleValue);
            u.setRole(newRole);
            changedUsers.add(u);
        }
        userRepository.saveAllAndFlush(users);
        for (User changedUser : changedUsers) {
            mutationAuditService.recordSuccess(
                    admin,
                    "BULK_ROLE_" + previousRoleByUser.get(changedUser.getId()) + "_TO_" + newRole.value(),
                    "user",
                    changedUser.getId(),
                    changedUser.getName(),
                    beforeByUser.get(changedUser.getId()),
                    MutationAuditService.userSnapshot(changedUser));
        }
        int changed = changedUsers.size();

        return ResponseEntity.ok(new BulkRoleReassignResponse(
                newRole.value(), changed, users.size() - changed, request.userIds().size()));
    }

    /**
     * PO-24: the leaver sweep. Nothing switches an account off by itself --
     * inactivity is not proof of departure, and somebody on parental leave
     * looks exactly like somebody who left -- so an administrator reviews the
     * "not seen in a long time" filter once a month and deactivates the rows
     * they recognise, in one decision rather than fifty.
     *
     * <p><b>No last-system-admin guard here</b>, unlike {@link
     * #bulkReassignRoles}: the acting administrator is dropped from the set
     * below, and {@code requireSystemAdmin} over authorization that
     * {@code JwtAuthenticationFilter} re-reads on every request means they are
     * an active system administrator at the moment this runs. One therefore
     * always survives the sweep, and a guard that cannot fire would read like
     * a rule that is doing something. {@code
     * bulkDeactivateCannotRemoveTheLastSystemAdmin} pins the reasoning.
     *
     * <p>Already-inactive rows are counted as skipped rather than re-saved, so
     * a second click does not write a second audit entry for an account that
     * was already switched off.
     */
    @PostMapping("/api/admin/users/bulk-deactivate")
    @Transactional
    public ResponseEntity<?> bulkDeactivateUsers(
            @Valid @RequestBody BulkDeactivateRequest request, @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }

        Set<Long> targetIds = new LinkedHashSet<>();
        for (Long id : request.userIds()) {
            if (id != null && !id.equals(admin.getId())) {
                targetIds.add(id);
            }
        }
        if (targetIds.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "detail", "არცერთი მომხმარებელი არ არის შესარჩევი (საკუთარი ანგარიშის დეაქტივაცია ჯგუფურად შეუძლებელია)."));
        }

        List<User> users = userRepository.findAllById(targetIds);
        if (users.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "მომხმარებლები ვერ მოიძებნა"));
        }

        int deactivated = 0;
        for (User user : users) {
            if (!user.isActive()) {
                continue;
            }
            Map<String, Object> before = MutationAuditService.userSnapshot(user);
            user.setActive(false);
            User saved = userRepository.saveAndFlush(user);
            mutationAuditService.recordSuccess(
                    admin,
                    "BULK_DEACTIVATE",
                    "user",
                    saved.getId(),
                    saved.getName(),
                    before,
                    MutationAuditService.userSnapshot(saved));
            deactivated++;
        }

        return ResponseEntity.ok(new BulkDeactivateResponse(
                deactivated, users.size() - deactivated, request.userIds().size()));
    }

    /** Port of update_user_status (routers/users.py:187-232). */
    @PutMapping("/api/users/{userId}/status")
    @Transactional
    public ResponseEntity<?> updateUserStatus(
            @PathVariable("userId") Long userId, @Valid @RequestBody UserStatusUpdateRequest request,
            @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "მომხმარებელი ვერ მოიძებნა"));
        }
        User user = found.get();

        if (user.getId().equals(admin.getId()) && !request.active()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("detail", "საკუთარი ანგარიშის დეაქტივაცია არ შეიძლება"));
        }

        if (!request.active() && user.getRole() == Role.SYSTEM_ADMIN) {
            long remaining = userRepository.countByRoleAndActiveTrueAndIdNotIn(
                    Role.SYSTEM_ADMIN, List.of(user.getId()));
            if (remaining == 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("detail", "ბოლო სისტემური ადმინისტრატორის დეაქტივაცია შეუძლებელია."));
            }
        }

        Map<String, Object> before = MutationAuditService.userSnapshot(user);
        user.setActive(request.active());
        User saved = userRepository.saveAndFlush(user);
        mutationAuditService.recordSuccess(
                admin,
                "UPDATE_STATUS_TO_" + String.valueOf(request.active()).toUpperCase(),
                "user",
                userId,
                saved.getName(),
                before,
                MutationAuditService.userSnapshot(saved));
        return ResponseEntity.ok(UserResponse.from(saved, permissionOverrideRepository.findByUserId(userId)));
    }

    /** Port of get_group_leaders (routers/users.py:235-251). */
    @GetMapping("/api/admin/group-leaders")
    public ResponseEntity<?> getGroupLeaders(@AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        List<GroupLeaderResponse> leaders = userDirectoryQueryService.listUsersByRoleWithinLimit(Role.MANAGER).stream()
                .map(GroupLeaderResponse::from)
                .toList();
        return ResponseEntity.ok(leaders);
    }

    /** Port of list_users (routers/users.py:254-296). */
    @GetMapping("/api/users")
    public ResponseEntity<?> listUsers(
            @RequestParam(value = "manager_id", required = false) Long managerId,
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "1000") int limit,
            @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        if (ListQueryBounds.isInvalid(skip, limit)) {
            return ResponseEntity.badRequest().body(Map.of("detail", ListQueryBounds.INVALID_DETAIL));
        }
        List<User> users = userDirectoryQueryService.list(managerId, skip, limit);

        Map<Long, ReadingProgress> progressByUser = complianceProgressQueryService.progressByUser(users);

        Map<Long, List<UserPermissionOverride>> overridesByUser = overridesByUser(users);
        List<UserResponse> responses = new ArrayList<>();
        for (User user : users) {
            ReadingProgress progress = progressByUser.get(user.getId());
            responses.add(UserResponse.from(
                    user, progress, overridesByUser.getOrDefault(user.getId(), List.of())));
        }
        return ResponseEntity.ok(responses);
    }

    /** Port of update_user_admin (routers/users.py:299-334). */
    @PutMapping("/api/users/{userId}")
    @Transactional
    public ResponseEntity<?> updateUserAdmin(
            @PathVariable("userId") Long userId, @Valid @RequestBody UserAdminUpdateRequest request,
            @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        Role role;
        try {
            role = Role.fromValue(request.role());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", "უცნობი როლი"));
        }
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "მომხმარებელი ვერ მოიძებნა"));
        }
        User user = found.get();
        // The drawer always sends the role it shows, so only a CHANGE is
        // refused; department, position and permission overrides stay the
        // portal's to edit.
        if (rolesManagedByDirectory() && role != user.getRole()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", ROLE_MANAGED_BY_DIRECTORY_DETAIL));
        }
        Map<String, Object> beforeUser = MutationAuditService.userSnapshot(user);

        // SEC-12: this endpoint had NEITHER guard that its two siblings
        // apply. bulkReassignRoles refuses to leave zero active system
        // admins (:186-199) and drops the caller from its own target list
        // (:172-176); updateUserStatus refuses self-deactivation (:246-249).
        // Here an admin could demote the last remaining SYSTEM_ADMIN --
        // including themselves -- and lock the organisation out of every
        // administrative screen with no way back through the product.
        if (user.getId().equals(admin.getId()) && role != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("detail", "საკუთარი როლის შეცვლა ამ გზით შეუძლებელია"));
        }
        ResponseEntity<Map<String, String>> lastAdminFailure = refuseIfLastSystemAdmin(user, role);
        if (lastAdminFailure != null) {
            return lastAdminFailure;
        }

        List<PermissionOverrideDelta> deltas = request.overrides();
        Map<String, Object> beforePermissions = deltas == null ? null : MutationAuditService.permissionSnapshot(
                permissionOverrideRepository.findByUserId(userId));
        if (deltas != null) {
            if (request.lockVersion() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("detail", "lock_version სავალდებულოა უფლებების ცვლილებისთვის"));
            }
            String validationError = permissionDeltaValidationError(deltas);
            if (validationError != null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", validationError));
            }
            ResponseEntity<?> conflict = validateUserLock(user, request.lockVersion());
            if (conflict != null) {
                return conflict;
            }
        }

        boolean userFieldsChanged = profileFieldsWouldChange(user, role, request);
        if (deltas != null && !userFieldsChanged) {
            ResponseEntity<?> conflict = advanceUserLock(userId, request.lockVersion());
            if (conflict != null) {
                return conflict;
            }
            user = userRepository.findById(userId).orElseThrow();
        }
        user.setRole(role);
        // Unlike phone/teamId below, department and position were assigned
        // with no null check, so an update omitting either silently blanked
        // it. department in particular drives every visibility and
        // compliance query the user appears in, so a null there quietly
        // removes them from their own department's obligations.
        if (request.department() != null) {
            user.setDepartment(request.department());
        }
        if (request.position() != null) {
            user.setPosition(request.position());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.teamId() != null) {
            user.setTeamId(request.teamId());
        }

        User saved = userRepository.saveAndFlush(user);
        if (deltas != null) {
            applyPermissionDeltas(userId, deltas, admin.getId());
            permissionOverrideRepository.flush();
        }
        if (userFieldsChanged) {
            mutationAuditService.recordSuccess(
                    admin,
                    "UPDATE_USER_ADMIN",
                    "user",
                    saved.getId(),
                    saved.getName(),
                    beforeUser,
                    MutationAuditService.userSnapshot(saved));
        }
        if (deltas != null && !deltas.isEmpty()) {
            mutationAuditService.recordSuccess(
                    admin,
                    "UPDATE_PERMISSIONS",
                    "user",
                    saved.getId(),
                    saved.getName(),
                    beforePermissions,
                    MutationAuditService.permissionSnapshot(permissionOverrideRepository.findByUserId(userId)));
        }
        return ResponseEntity.ok(UserResponse.from(saved, permissionOverrideRepository.findByUserId(saved.getId())));
    }

    /** Port of get_teams (routers/users.py:337-346). */
    @GetMapping("/api/teams")
    public ResponseEntity<?> getTeams(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        List<TeamResponse> teams = orgDirectoryQueryService.listTeamsWithinLimit().stream()
                .map(TeamResponse::from).toList();
        return ResponseEntity.ok(teams);
    }

    /**
     * Group identity is directory-owned. Read access remains available via
     * {@link #getTeams}; interactive creation is fail-closed before the V36
     * expand window so no row can bypass the later department/external-id
     * uniqueness contracts. Dev fixtures use the explicit seeder instead.
     */
    @PostMapping("/api/teams")
    @Transactional
    public ResponseEntity<?> createTeam(@Valid @RequestBody TeamRequest request, @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("detail", "ჯგუფების შექმნა იმართება ორგანიზაციის კატალოგიდან"));
    }

    /** Port of create_user_admin (routers/users.py:394-427). */
    @PostMapping("/api/users")
    @Transactional
    public ResponseEntity<?> createUserAdmin(
            @Valid @RequestBody UserCreateAdminRequest request, @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "detail", "მომხმარებლების შექმნა იმართება კომპანიის Active Directory-იდან სინქრონიზაციით."));
    }

    /** Port of admin_reset_password (routers/users.py:431-444). */
    @PostMapping("/api/users/{userId}/reset-password")
    @Transactional
    public ResponseEntity<?> adminResetPassword(
            @PathVariable("userId") Long userId, @Valid @RequestBody AdminPasswordResetRequest request,
            @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "detail", "პაროლის აღდგენა იმართება კომპანიის Active Directory-ში."));
    }

    /** Phase 6 override-aware permission delta with optimistic concurrency. */
    @PutMapping("/api/users/{userId}/permissions")
    @Transactional
    public ResponseEntity<?> adminUpdatePermissions(
            @PathVariable("userId") Long userId, @Valid @RequestBody PermissionsUpdateRequest request,
            @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        String validationError = permissionDeltaValidationError(request.overrides());
        if (validationError != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", validationError));
        }
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "მომხმარებელი ვერ მოიძებნა"));
        }
        User user = found.get();
        ResponseEntity<?> conflict = validateUserLock(user, request.lockVersion());
        if (conflict != null) {
            return conflict;
        }
        conflict = advanceUserLock(userId, request.lockVersion());
        if (conflict != null) {
            return conflict;
        }
        user = userRepository.findById(userId).orElseThrow();

        Map<String, Object> beforePermissions = MutationAuditService.permissionSnapshot(
                permissionOverrideRepository.findByUserId(userId));
        applyPermissionDeltas(userId, request.overrides(), admin.getId());
        permissionOverrideRepository.flush();
        mutationAuditService.recordSuccess(
                admin,
                "UPDATE_PERMISSIONS",
                "user",
                userId,
                user.getName(),
                beforePermissions,
                MutationAuditService.permissionSnapshot(permissionOverrideRepository.findByUserId(userId)));
        return ResponseEntity.ok(UserResponse.from(user, permissionOverrideRepository.findByUserId(userId)));
    }

    private String permissionDeltaValidationError(List<PermissionOverrideDelta> deltas) {
        List<String> unknown = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (PermissionOverrideDelta delta : deltas) {
            try {
                Permission.fromValue(delta.permission());
            } catch (IllegalArgumentException e) {
                unknown.add(delta.permission());
            }
            if (!seen.add(delta.permission())) {
                duplicates.add(delta.permission());
            }
        }
        if (!unknown.isEmpty()) {
            return "უცნობი უფლება(ები): " + String.join(", ", unknown);
        }
        if (!duplicates.isEmpty()) {
            return "დუბლირებული უფლება(ები): " + String.join(", ", duplicates);
        }
        return null;
    }

    private ResponseEntity<?> validateUserLock(User user, long expectedVersion) {
        if (user.getLockVersion() != expectedVersion) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "detail", "მომხმარებლის მონაცემები შეიცვალა. განაახლეთ გვერდი და სცადეთ თავიდან.",
                    "lock_version", user.getLockVersion()));
        }
        return null;
    }

    private ResponseEntity<?> advanceUserLock(Long userId, long expectedVersion) {
        if (userRepository.advanceLockVersion(userId, expectedVersion) != 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "detail", "მომხმარებლის მონაცემები შეიცვალა. განაახლეთ გვერდი და სცადეთ თავიდან."));
        }
        return null;
    }

    private boolean profileFieldsWouldChange(User user, Role role, UserAdminUpdateRequest request) {
        return user.getRole() != role
                || request.department() != null && !Objects.equals(user.getDepartment(), request.department())
                || request.position() != null && !Objects.equals(user.getPosition(), request.position())
                || request.phone() != null && !Objects.equals(user.getPhone(), request.phone())
                || request.teamId() != null && !Objects.equals(user.getTeamId(), request.teamId());
    }

    private List<String> applyPermissionDeltas(
            Long userId, List<PermissionOverrideDelta> deltas, Long actorId) {
        Map<String, UserPermissionOverride> existing = new HashMap<>();
        for (UserPermissionOverride override : permissionOverrideRepository.findByUserId(userId)) {
            existing.put(override.getPermission(), override);
        }
        OffsetDateTime now = TbilisiTime.now();
        List<String> transitions = new ArrayList<>();
        for (PermissionOverrideDelta delta : deltas) {
            UserPermissionOverride current = existing.get(delta.permission());
            String before = current == null ? "INHERIT" : current.getState().name();
            String after = delta.state().name();
            if (before.equals(after)) {
                continue;
            }
            transitions.add(delta.permission() + " " + before + "->" + after);
            if (delta.state() == PermissionOverrideDelta.State.INHERIT) {
                permissionOverrideRepository.delete(current);
                continue;
            }
            UserPermissionOverride override = current == null ? new UserPermissionOverride() : current;
            override.setUserId(userId);
            override.setPermission(delta.permission());
            override.setState(UserPermissionOverride.State.valueOf(delta.state().name()));
            override.setUpdatedAt(now);
            override.setUpdatedBy(actorId);
            permissionOverrideRepository.save(override);
        }
        return transitions;
    }

    private Map<Long, List<UserPermissionOverride>> overridesByUser(List<User> users) {
        List<Long> ids = users.stream().map(User::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<UserPermissionOverride>> result = new HashMap<>();
        for (UserPermissionOverride override : permissionOverrideRepository.findByUserIdIn(ids)) {
            result.computeIfAbsent(override.getUserId(), ignored -> new ArrayList<>()).add(override);
        }
        return result;
    }

    /**
     * SEC-12: the last-active-admin check, extracted so
     * {@link #updateUserAdmin} and {@link #bulkReassignRoles} cannot drift
     * apart again -- the bulk path had it, the single-user path did not, and
     * the single-user path is the one an administrator actually clicks.
     *
     * @return a 400 response when this change would leave zero active system
     * admins, or null when it is safe
     */
    private ResponseEntity<Map<String, String>> refuseIfLastSystemAdmin(User target, Role newRole) {
        if (target.getRole() != Role.SYSTEM_ADMIN || newRole == Role.SYSTEM_ADMIN) {
            return null;
        }
        long remaining = userRepository.countByRoleAndActiveTrueAndIdNotIn(Role.SYSTEM_ADMIN, List.of(target.getId()));
        if (remaining > 0) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("detail", "ბოლო სისტემური ადმინისტრატორის როლის შეცვლა შეუძლებელია."));
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }
}
