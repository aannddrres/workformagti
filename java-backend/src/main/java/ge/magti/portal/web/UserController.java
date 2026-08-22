package ge.magti.portal.web;

import ge.magti.portal.announcement.BroadcastAuthorizationService;
import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.compliance.ReadCountKey;
import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.security.PasswordPolicy;
import ge.magti.portal.security.PermissionChecker;
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
 * silently rerouting it through the {@link ge.magti.portal.domain.Message}
 * table (a real behavior change, not a straight port). User chose to defer
 * it outright, same rationale as {@code GET /api/stream}
 * ({@link MessagingController}) -- it rides the exact same SSE broker.
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
    private final TeamRepository teamRepository;
    private final AuditLogRepository auditLogRepository;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionChecker permissionChecker;
    private final UserPermissionOverrideRepository permissionOverrideRepository;
    private final BroadcastAuthorizationService broadcastAuthorizationService;

    public UserController(
            UserRepository userRepository, TeamRepository teamRepository, AuditLogRepository auditLogRepository,
            RequiredReadingRepository requiredReadingRepository, ReadStatusRepository readStatusRepository,
            PasswordEncoder passwordEncoder, PermissionChecker permissionChecker,
            UserPermissionOverrideRepository permissionOverrideRepository,
            BroadcastAuthorizationService broadcastAuthorizationService) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.auditLogRepository = auditLogRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionChecker = permissionChecker;
        this.permissionOverrideRepository = permissionOverrideRepository;
        this.broadcastAuthorizationService = broadcastAuthorizationService;
    }

    /** Port of read_users_me (routers/users.py:29-50). */
    @GetMapping("/api/users/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        boolean canViewAuditLog = permissionChecker.hasPermission(user, Permission.SYSTEM_AUDIT);
        // The permission list has to be the one the gates use. Since the
        // Phase 6 cutover users.permissions decides nothing, so shipping it
        // here would show the account page a set of abilities that no longer
        // matches what the caller can actually do.
        return ResponseEntity.ok(CurrentUserResponse.from(
                user, canViewAuditLog, permissionChecker.effectivePermissions(user)));
    }

    /** The authenticated caller's effective capabilities for client-side access decisions. */
    @GetMapping("/api/me/effective-access")
    public ResponseEntity<?> getEffectiveAccess(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
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
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        user.setName(request.name());
        if (request.position() != null) {
            user.setPosition(request.position());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.cardStyle() != null) {
            user.setCardStyle(request.cardStyle());
        }
        User saved = userRepository.save(user);
        return ResponseEntity.ok(UserResponse.from(saved, permissionOverrideRepository.findByUserId(saved.getId())));
    }

    /** Port of change_own_password (routers/users.py:73-97). */
    @PostMapping("/api/users/me/password")
    @Transactional
    public ResponseEntity<?> changeOwnPassword(
            @Valid @RequestBody PasswordChangeRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        if (user.getHashedPassword() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getHashedPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", "მიმდინარე პაროლი არასწორია"));
        }
        if (request.newPassword().equals(request.currentPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("detail", "ახალი პაროლი არ უნდა ემთხვეოდეს ძველს"));
        }
        List<String> policyErrors = PasswordPolicy.validate(request.newPassword());
        if (!policyErrors.isEmpty()) {
            return passwordPolicyError(policyErrors);
        }

        user.setHashedPassword(passwordEncoder.encode(request.newPassword()));
        // SEC-14. Changing a password because you think someone else has it
        // is worthless if their token keeps working for the rest of its
        // hour. Ends every session including this one -- the response says
        // so, since the alternative (reissuing a token here) would mean a
        // new contract on an endpoint the Angular app does not yet call.
        user.invalidateIssuedTokens();
        userRepository.save(user);

        AuditLog audit = new AuditLog();
        audit.setAdminId(user.getId());
        audit.setAction("PASSWORD_CHANGE");
        audit.setItemType("user");
        audit.setItemId(user.getId());
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);

        return ResponseEntity.ok(Map.of(
                "detail", "პაროლი წარმატებით შეიცვალა. ყველა სესია დასრულდა — გთხოვთ, თავიდან შეხვიდეთ."));
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

        int changed = 0;
        for (User u : users) {
            if (u.getRole() == newRole) {
                continue;
            }
            String oldRoleValue = u.getRole().value();
            u.setRole(newRole);

            AuditLog audit = new AuditLog();
            audit.setAdminId(admin.getId());
            audit.setAction("BULK_ROLE_" + oldRoleValue + "_TO_" + newRole.value());
            audit.setItemType("user");
            audit.setItemId(u.getId());
            audit.setTimestamp(TbilisiTime.now());
            auditLogRepository.save(audit);
            changed++;
        }
        userRepository.saveAll(users);

        return ResponseEntity.ok(new BulkRoleReassignResponse(
                newRole.value(), changed, users.size() - changed, request.userIds().size()));
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

        user.setActive(request.active());

        AuditLog audit = new AuditLog();
        audit.setAdminId(admin.getId());
        audit.setAction("UPDATE_STATUS_TO_" + String.valueOf(request.active()).toUpperCase());
        audit.setItemType("user");
        audit.setItemId(userId);
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);

        User saved = userRepository.save(user);
        return ResponseEntity.ok(UserResponse.from(saved, permissionOverrideRepository.findByUserId(userId)));
    }

    /** Port of get_group_leaders (routers/users.py:235-251). */
    @GetMapping("/api/admin/group-leaders")
    public ResponseEntity<?> getGroupLeaders(@AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        List<GroupLeaderResponse> leaders = userRepository.findByRoleOrderByName(Role.MANAGER).stream()
                .map(GroupLeaderResponse::from)
                .toList();
        return ResponseEntity.ok(leaders);
    }

    /** Port of list_users (routers/users.py:254-296). */
    @GetMapping("/api/users")
    public ResponseEntity<?> listUsers(
            @RequestParam(value = "manager_id", required = false) Long managerId,
            @AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        List<User> users = managerId != null ? userRepository.findByManagerId(managerId) : userRepository.findAll();

        Map<String, Integer> requiredCountsByDept = new HashMap<>();
        for (Object[] row : requiredReadingRepository.countGroupedByTargetDepartment()) {
            requiredCountsByDept.put((String) row[0], ((Number) row[1]).intValue());
        }
        int allRequired = requiredCountsByDept.getOrDefault("All", 0);

        List<Long> userIds = users.stream().map(User::getId).toList();
        Map<ReadCountKey, Integer> readCountsByUserDept = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Object[] row : readStatusRepository.readCountsByUserAndDepartment(userIds)) {
                readCountsByUserDept.put(
                        new ReadCountKey(((Number) row[0]).longValue(), (String) row[1]),
                        ((Number) row[2]).intValue());
            }
        }

        Map<Long, List<UserPermissionOverride>> overridesByUser = overridesByUser(users);
        List<UserResponse> responses = new ArrayList<>();
        for (User user : users) {
            ReadingProgress progress = ComplianceCalculator.computeProgress(
                    user, allRequired, requiredCountsByDept, readCountsByUserDept);
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
            List<String> transitions = applyPermissionDeltas(userId, deltas, admin.getId());
            if (!deltas.isEmpty()) {
                auditPermissionUpdate(userId, admin.getId(), transitions);
            }
            permissionOverrideRepository.flush();
        }
        return ResponseEntity.ok(UserResponse.from(saved, permissionOverrideRepository.findByUserId(saved.getId())));
    }

    /** Port of get_teams (routers/users.py:337-346). */
    @GetMapping("/api/teams")
    public ResponseEntity<?> getTeams(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        List<TeamResponse> teams = teamRepository.findAllByOrderByName().stream().map(TeamResponse::from).toList();
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
        List<String> policyErrors = PasswordPolicy.validate(request.password());
        if (!policyErrors.isEmpty()) {
            return passwordPolicyError(policyErrors);
        }
        Role role;
        try {
            role = Role.fromValue(request.roleOrDefault());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", "უცნობი როლი"));
        }
        String lowerEmail = request.email().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(lowerEmail).isPresent()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", "ეს ელ. ფოსტა უკვე გამოყენებულია"));
        }

        User user = new User();
        user.setEmail(lowerEmail);
        user.setName(request.name());
        user.setDepartment(request.department());
        user.setPosition(request.position());
        user.setPhone(request.phone());
        user.setRole(role);
        user.setHashedPassword(passwordEncoder.encode(request.password()));
        user.setActive(true);
        user.setTeamId(request.teamId());
        Set<String> defaultPerms = new LinkedHashSet<>();
        for (Permission p : Permission.defaultsFor(role)) {
            defaultPerms.add(p.value());
        }
        user.setPermissions(defaultPerms);

        User saved = userRepository.saveAndFlush(user);

        AuditLog audit = new AuditLog();
        audit.setAdminId(admin.getId());
        audit.setAction("CREATE_USER");
        audit.setItemType("user");
        audit.setItemId(saved.getId());
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);

        // Empty overrides here are the truth, not an omission: a user created
        // one statement ago has none, and everything they can do comes from
        // the role default.
        return ResponseEntity.ok(UserResponse.from(saved, List.<UserPermissionOverride>of()));
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
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "მომხმარებელი ვერ მოიძებნა"));
        }
        List<String> policyErrors = PasswordPolicy.validate(request.newPassword());
        if (!policyErrors.isEmpty()) {
            return passwordPolicyError(policyErrors);
        }
        User user = found.get();
        user.setHashedPassword(passwordEncoder.encode(request.newPassword()));
        // SEC-14. An admin resetting someone else's password is usually a
        // response to a suspected compromise, so cutting the existing
        // sessions is the point of it. The admin is not the target here, so
        // this costs nobody their own session.
        user.invalidateIssuedTokens();
        userRepository.save(user);

        AuditLog audit = new AuditLog();
        audit.setAdminId(admin.getId());
        audit.setAction("PASSWORD_RESET");
        audit.setItemType("user");
        audit.setItemId(userId);
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);

        return ResponseEntity.ok(Map.of("detail", "პაროლი წარმატებით აღდგა."));
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

        List<String> transitions = applyPermissionDeltas(userId, request.overrides(), admin.getId());
        auditPermissionUpdate(userId, admin.getId(), transitions);

        permissionOverrideRepository.flush();
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

    private void auditPermissionUpdate(Long userId, Long actorId, List<String> transitions) {
        AuditLog audit = new AuditLog();
        audit.setAdminId(actorId);
        audit.setAction("UPDATE_PERMISSIONS");
        audit.setItemType("user");
        audit.setItemId(userId);
        // Rule #9 is that granting a content permission is audited. An action
        // name alone does not say what was granted, to whom it already
        // belonged, or whether anything moved at all -- and "which permissions
        // did this person hold on the day they published that article" is the
        // question the record has to answer later. A no-op delta is recorded
        // as a no-op rather than as an indistinguishable UPDATE_PERMISSIONS.
        audit.setDetails(transitions.isEmpty() ? "no change" : String.join(", ", transitions));
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);
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

    private static ResponseEntity<Map<String, String>> passwordPolicyError(List<String> errors) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("detail", "პაროლი ვერ აკმაყოფილებს მოთხოვნებს: " + String.join(", ", errors)));
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
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
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }
}
