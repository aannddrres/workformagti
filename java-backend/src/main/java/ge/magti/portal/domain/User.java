package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The users table.
 *
 * <p>Deliberately NOT modeled: {@code team}/{@code manager} as object
 * references -- {@link #teamId}/{@link #managerId} carry
 * the raw foreign key only. Wiring the actual object graph is repository
 * work, not a data-structure decision.
 *
 * <p>{@link #lastActive}/{@link #lastNewsViewedAt} are {@link
 * OffsetDateTime}, not {@link java.time.Instant}, on purpose: every
 * timestamp in this table is naive Tbilisi local time (UTC+4 wall-clock
 * time with the zone stripped), not UTC.
 * Decided 2026-07-29: keep storing Tbilisi time (not UTC) in the Java port
 * too -- see {@link ge.magti.portal.util.TbilisiTime} and {@link
 * ge.magti.portal.util.TbilisiTimestampConverter}, which maps this back to
 * a plain (zoneless) {@code TIMESTAMP(6)} column; only the Java-side type
 * gets the offset made explicit.
 *
 * <p>{@code last_categories_viewed_at} (a JSON column) is deliberately NOT
 * carried over to the Oracle schema:
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
    /**
     * A system admin's explicit answer to "is this person subject to mandatory
     * reading?", overriding whatever the eligibility policy would decide.
     *
     * <p>Three-valued on purpose, and {@code null} is the normal case: null
     * means "follow the policy", TRUE forces the person in, FALSE forces them
     * out. A two-valued column would have to be initialised to the policy's
     * current answer for every existing row, which freezes today's rule as
     * explicit data and makes a later policy change a no-op for everyone who
     * predates it.
     *
     * <p>Nothing reads this yet -- {@code ComplianceEligibilityService}
     * (Phase 5) does. V36 adds the column so the schema and the policy can
     * ship in separate releases.
     */
    @Column(name = "compliance_override")
    private Boolean complianceOverride;

    /**
     * Phase 6 optimistic lock. Deliberately separate from tokenVersion:
     * changing a profile or permission must detect stale writes without
     * revoking every issued access token.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

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

    /**
     * Whether this person reads content as its manager does -- every
     * department, every editorial status -- decided once per request by
     * {@code JwtAuthenticationFilter} from the effective {@code content.manage}
     * capability. Null on any User not loaded as the signed-in caller.
     */
    @Transient
    private Boolean seesAllContent;

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission.value());
    }

    /**
     * The content-administration reach, for the visibility rules
     * (ArticleVisibility, NewsVisibility, VideoVisibility and the list and
     * search queries).
     *
     * <p>It used to be the role alone. An administrator could grant a manager
     * {@code content.manage} and {@code articles.edit}, and the manager could
     * then overwrite a draft they got a 404 for when they tried to open it --
     * write access to what they could not read (audit 2026-10-01; owner's
     * decision: whoever may manage content sees what content administrators
     * see). The DENY direction follows: a content administrator refused
     * {@code content.manage} reads like anyone else in their department.
     *
     * <p>Where the capability was not resolved -- a User built in a test, or
     * loaded as data rather than as the caller -- the role still answers.
     */
    public boolean seesAllContent() {
        return seesAllContent != null ? seesAllContent : role.isContentAdmin();
    }

    public void setSeesAllContent(Boolean seesAllContent) {
        this.seesAllContent = seesAllContent;
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

    public Boolean getComplianceOverride() {
        return complianceOverride;
    }

    public void setComplianceOverride(Boolean complianceOverride) {
        this.complianceOverride = complianceOverride;
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

    public long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(long lockVersion) {
        this.lockVersion = lockVersion;
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
