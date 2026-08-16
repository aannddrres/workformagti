package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mirrors models.py's User (models.py:22-55) -- the users table.
 *
 * <p>Deliberately NOT modeled: {@code team}/{@code manager} as object
 * references (models.py:54-55) -- {@link #teamId}/{@link #managerId} carry
 * the raw foreign key only. Wiring the actual object graph is repository
 * work, not a data-structure decision.
 *
 * <p>{@link #lastActive}/{@link #lastNewsViewedAt} are {@link
 * OffsetDateTime}, not {@link java.time.Instant}, on purpose: database.py's
 * get_tbilisi_time() (database.py:69-71) takes UTC+4 wall-clock time and
 * then STRIPS the timezone before returning it, so every timestamp in this
 * table today is naive Tbilisi local time with no zone attached, not UTC.
 * Decided 2026-07-29: keep storing Tbilisi time (not UTC) in the Java port
 * too -- see {@link ge.magti.portal.util.TbilisiTime} and {@link
 * ge.magti.portal.util.TbilisiTimestampConverter}, which maps this back to
 * a plain (zoneless) {@code TIMESTAMP(6)} column so the physical storage
 * matches the Python side exactly; only the Java-side type gets the offset
 * made explicit.
 *
 * <p>{@code last_categories_viewed_at} (models.py:51, JSON, default
 * {@code dict}) is deliberately NOT carried over to the Oracle schema:
 * grepped repo-wide, no router/template/script anywhere reads or writes it
 * -- confirmed dead, and you chose to drop it rather than reserve space for
 * it (2026-07-30).
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uq_users_email", columnNames = "email"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "department", length = 200)
    private String department;

    @Column(name = "position", length = 200)
    private String position;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "role", length = 30)
    private Role role = Role.OPERATOR;

    @Column(name = "is_active")
    private boolean active = true;

    /**
     * SEC-14. Every access token is minted carrying this value as its
     * {@code tv} claim, and {@link ge.magti.portal.security.JwtAuthenticationFilter}
     * refuses a token whose claim no longer matches. Incrementing it is what
     * makes logout mean something: without it, clearing the cookie left the
     * token itself valid for its remaining 60 minutes.
     *
     * <p>Not a JPA {@code @Version}: this is application state that is
     * incremented deliberately, not an optimistic lock. See
     * {@code V33__article_optimistic_lock.sql} for the same distinction
     * drawn on Article.
     */
    @Column(name = "token_version", nullable = false)
    private long tokenVersion = 0L;

    @Column(name = "last_active")
    private OffsetDateTime lastActive;

    @Column(name = "hashed_password", length = 255)
    private String hashedPassword;

    // @Lob: without it, Hibernate infers the converted (String) column as a
    // default-length VARCHAR2(255), not the CLOB the migration actually
    // creates -- schema validation failed on exactly this before @Lob was
    // added (confirmed against the real Oracle instance, not assumed).
    @Lob
    @Convert(converter = PermissionsConverter.class)
    @Column(name = "permissions")
    private Set<String> permissions = new LinkedHashSet<>();

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "last_news_viewed_at")
    private OffsetDateTime lastNewsViewedAt;

    @Column(name = "card_style", length = 50)
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

    public long getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(long tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    /** Invalidates every access token already issued to this user (SEC-14). */
    public void invalidateIssuedTokens() {
        this.tokenVersion++;
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

    public String getCardStyle() {
        return cardStyle;
    }

    public void setCardStyle(String cardStyle) {
        this.cardStyle = cardStyle;
    }
}
