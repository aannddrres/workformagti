package ge.magti.portal.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mirrors models.py's User (models.py:22-55) -- the users table exactly as
 * it exists today, not yet mapped to Oracle. No persistence annotations on
 * purpose: that's Phase 1b work, once the Oracle column types/lengths (68
 * of 202 columns portal-wide currently have neither -- see
 * docs/JAVA_ORACLE_ANGULAR_MIGRATION.md Phase 1) are consciously decided
 * rather than guessed here. This class only fixes the *shape*.
 *
 * <p>Deliberately NOT modeled: {@code team}/{@code manager} as object
 * references (models.py:54-55) -- {@link #teamId}/{@link #managerId} carry
 * the raw foreign key only. Wiring the actual object graph is repository
 * work, not a data-structure decision.
 *
 * <p>Two things worth knowing before this is ever wired to a real column:
 * <ul>
 *   <li>{@link #lastActive}/{@link #lastNewsViewedAt} are
 *       {@link OffsetDateTime}, not {@link java.time.Instant}, on purpose:
 *       database.py's get_tbilisi_time() (database.py:69-71) takes UTC+4
 *       wall-clock time and then STRIPS the timezone before returning it,
 *       so every timestamp in this table today is naive Tbilisi local time
 *       with no zone attached, not UTC. Decided 2026-07-29: keep storing
 *       Tbilisi time (not UTC) in the Java port too -- see
 *       {@link ge.magti.portal.util.TbilisiTime}. The one change from the
 *       Python side is that the zone is now explicit in the type rather
 *       than a silently-assumed naive value, which is what closes the risk
 *       that mapping this to Instant would have silently shifted every
 *       value by 4 hours.</li>
 *   <li>{@link #lastCategoriesViewedAt} mirrors a column (models.py:51,
 *       JSON, default {@code dict}) that no router, template, or script
 *       anywhere in the current repo reads or writes -- grepped repo-wide,
 *       zero hits outside models.py/migrate.py. It may simply be dead.
 *       Carried over as a raw, untyped placeholder rather than dropped
 *       silently or guessed at; worth confirming with the org before Phase
 *       1b decides its Oracle type. Logged as an additional finding in the
 *       migration doc.</li>
 * </ul>
 */
public class User {

    private Long id;
    private String email;
    private String name;
    private String department;
    private String position;
    private String phone;
    private Role role = Role.OPERATOR;
    private boolean active = true;
    private OffsetDateTime lastActive;
    private String hashedPassword;
    private Set<String> permissions = new LinkedHashSet<>();
    private Long teamId;
    private Long managerId;
    private OffsetDateTime lastNewsViewedAt;
    private Object lastCategoriesViewedAt;
    private String cardStyle = "corporate";

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission.value());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getLastActive() {
        return lastActive;
    }

    public void setLastActive(OffsetDateTime lastActive) {
        this.lastActive = lastActive;
    }

    public String getHashedPassword() {
        return hashedPassword;
    }

    public void setHashedPassword(String hashedPassword) {
        this.hashedPassword = hashedPassword;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public OffsetDateTime getLastNewsViewedAt() {
        return lastNewsViewedAt;
    }

    public void setLastNewsViewedAt(OffsetDateTime lastNewsViewedAt) {
        this.lastNewsViewedAt = lastNewsViewedAt;
    }

    public Object getLastCategoriesViewedAt() {
        return lastCategoriesViewedAt;
    }

    public void setLastCategoriesViewedAt(Object lastCategoriesViewedAt) {
        this.lastCategoriesViewedAt = lastCategoriesViewedAt;
    }

    public String getCardStyle() {
        return cardStyle;
    }

    public void setCardStyle(String cardStyle) {
        this.cardStyle = cardStyle;
    }
}
