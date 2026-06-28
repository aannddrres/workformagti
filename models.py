from typing import Optional
from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    JSON,
    String,
    Text,
    UniqueConstraint,
    event,
    text,
)
from sqlalchemy import inspect as sa_inspect
from sqlalchemy.orm import relationship
from database import Base, get_tbilisi_time


class User(Base):
    __tablename__ = "users"
    # AUTOINCREMENT so deleted ids are never reused; audit_logs references rows by item_id
    __table_args__ = (
        Index("ix_users_department_active", "department", "is_active"),
        {"sqlite_autoincrement": True},
    )

    id = Column(Integer, primary_key=True, index=True)
    email = Column(String, unique=True, index=True, nullable=False)
    name = Column(String, nullable=False)
    department = Column(String, index=True)
    position = Column(String)
    phone = Column(String, nullable=True)
    role = Column(String, default="operator", index=True)  # operator | manager | content_admin | admin
    is_active = Column(Boolean, default=True)
    last_active = Column(DateTime, nullable=True)
    hashed_password = Column(String, nullable=True)
    # Spec slide 26: granular admin permissions on top of the role.
    # system_admin role implicitly bypasses these. List of strings, e.g.
    # ["articles.publish", "users.manage", "reports.export"].
    permissions = Column(JSON, default=list, nullable=True)
    # Team Statistics foundation: optional, nullable so existing rows are
    # unaffected until an admin assigns a team.
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=True, index=True)
    # Block 5: self-referencing FK — the team_lead (or other manager-role user)
    # this user reports to. Nullable: most users have no manager assigned yet.
    manager_id = Column(Integer, ForeignKey("users.id"), nullable=True, index=True)
    last_news_viewed_at = Column(DateTime, nullable=True)
    last_categories_viewed_at = Column(JSON, default=dict, nullable=True)
    card_style = Column(String, default="corporate", nullable=True)

    team = relationship("Team", back_populates="members")
    manager = relationship("User", remote_side=[id], backref="direct_reports")


class News(Base):
    __tablename__ = "news"

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String, index=True, nullable=False)
    content = Column(Text, nullable=False)
    target_department = Column(String, default="All", index=True)
    created_at = Column(DateTime, default=get_tbilisi_time)
    # Centralised attachment per spec (PDF/image/video link). Added 2026.
    attachment_url = Column(String, nullable=True)
    # Version counter; mirrors articles for parity in the admin history UI.
    version = Column(Integer, default=1)
    # Block 5: role-based content visibility — see Article for the rationale.
    visible_to_tech_info = Column(Boolean, default=True, nullable=False)
    visible_to_service_center = Column(Boolean, default=False, nullable=False)
    expires_at = Column(DateTime, nullable=True)
    is_draft = Column(Boolean, default=True, nullable=False)
    author_id = Column(Integer, ForeignKey("users.id"), nullable=True)

    @property
    def is_archived(self) -> bool:
        return self.expires_at is not None and self.expires_at < get_tbilisi_time()



class NewsHistory(Base):
    """Per-news revision history — parity with ArticleHistory so admins can restore."""
    __tablename__ = "news_history"

    id = Column(Integer, primary_key=True, index=True)
    news_id = Column(Integer, ForeignKey("news.id"), nullable=False, index=True)
    title = Column(String, nullable=False)
    content = Column(Text, nullable=False)
    attachment_url = Column(String, nullable=True)
    updated_at = Column(DateTime, default=get_tbilisi_time)
    updated_by = Column(Integer, ForeignKey("users.id"), nullable=False)


class Category(Base):
    __tablename__ = "categories"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, index=True, nullable=False)
    parent_id = Column(Integer, ForeignKey("categories.id"), nullable=True)
    # Taxonomy grid metadata: URL-safe slug (#/category/{slug}) + Font Awesome icon id.
    slug = Column(String, index=True, nullable=True)
    icon = Column(String, nullable=True)
    pastel_color_class = Column(String, nullable=True)
    is_active = Column(Boolean, default=True)

    articles = relationship("Article", back_populates="category")


class Article(Base):
    __tablename__ = "articles"
    # AUTOINCREMENT so deleted ids are never reused; audit_logs references rows by item_id
    __table_args__ = (
        # Operators filter on (target_department, status); this composite index
        # serves that hot path on PostgreSQL.
        Index("ix_articles_department_status", "target_department", "status"),
        {"sqlite_autoincrement": True},
    )

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String, index=True, nullable=False)
    content = Column(Text, nullable=False)
    category_id = Column(Integer, ForeignKey("categories.id"), index=True)
    tags = Column(String, nullable=True)
    target_department = Column(String, default="All")
    # Audience partition: 'info' (subscriber/commercial), 'tech' (engineering/
    # infrastructure), or 'all'. Drives the context-aware KB category view.
    audience_profile = Column(String, default="all", index=True)
    created_at = Column(DateTime, default=get_tbilisi_time)
    updated_at = Column(DateTime, default=get_tbilisi_time, onupdate=get_tbilisi_time)
    version = Column(Integer, default=1)
    author_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    status = Column(String, default="draft", server_default="draft")
    youtube_id = Column(String, nullable=True)
    # Future publish time for status='scheduled'. Referenced by get_articles and
    # _assert_article_visible in main.py and by ArticleBase in schemas.py.
    published_at = Column(DateTime, nullable=True)
    attachment_url = Column(String, nullable=True)
    last_verified_at = Column(DateTime, nullable=True, default=get_tbilisi_time)
    # Block 5: role-based content visibility (distinct from audience_profile,
    # which is single-valued — these are independent flags so content can be
    # shown to both groups, one, or neither).
    visible_to_tech_info = Column(Boolean, default=True, nullable=False)
    visible_to_service_center = Column(Boolean, default=False, nullable=False)
    is_draft = Column(Boolean, default=True, nullable=False)


    category = relationship("Category", back_populates="articles")
    # Named distinctly from the target_departments *property* below: this is the
    # raw ORM relationship (ArticleTargetDepartment rows), used at the class level
    # for query filtering (e.g. Article.target_department_rows.any(...)).
    target_department_rows = relationship(
        "ArticleTargetDepartment", cascade="all, delete-orphan", lazy="selectin"
    )

    @property
    def category_name(self) -> Optional[str]:
        """Joined Category.name for admin list views; relies on eager loading."""
        return self.category.name if self.category else None

    @property
    def target_departments(self) -> list[str]:
        """Multi-department targets via the article_target_departments junction table."""
        return [t.department for t in self.target_department_rows]

    @property
    def read_time(self) -> int:
        """Estimates reading time in minutes based on Georgian content word count.

        Returns the 1-minute default without triggering a lazy-load when `content`
        was deferred (e.g. bulk list queries) — accessing it there would re-fetch
        the full HTML body per row, reintroducing the cost the defer() avoids.
        """
        if "content" in sa_inspect(self).unloaded:
            return 1
        if not self.content:
            return 1
        word_count = len(self.content.split())
        return max(1, int(word_count / 150))


class ArticleTargetDepartment(Base):
    """Junction table letting one Article target multiple departments (or 'All').

    Coexists with Article.target_department (kept for now, not yet dropped) during
    the migration window — see main.py's startup migration for the backfill.
    """
    __tablename__ = "article_target_departments"

    article_id = Column(Integer, ForeignKey("articles.id", ondelete="CASCADE"), primary_key=True)
    department = Column(String, primary_key=True, index=True)


class RequiredReading(Base):
    __tablename__ = "required_readings"
    # AUTOINCREMENT so deleted ids are never reused; audit_logs references rows by item_id
    __table_args__ = (
        Index("ix_required_readings_department", "target_department"),
        {"sqlite_autoincrement": True},
    )

    id = Column(Integer, primary_key=True, index=True)
    item_type = Column(String, nullable=False)
    item_id = Column(Integer, nullable=False)
    target_department = Column(String, default="All")
    due_date = Column(DateTime, nullable=False)
    priority = Column(String, default="normal")


class ReadStatus(Base):
    __tablename__ = "read_statuses"
    __table_args__ = (
        # Data integrity: a user has exactly one status row per required reading.
        UniqueConstraint(
            "user_id", "required_reading_id", name="uq_read_status_user_reading"
        ),
        Index("ix_read_status_reading_status", "required_reading_id", "status"),
    )

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    required_reading_id = Column(
        Integer, ForeignKey("required_readings.id"), nullable=False
    )
    status = Column(String, default="unread")
    read_at = Column(DateTime, nullable=True)
    # Immutable snapshot of User.department at the moment this row was marked
    # "read" — prevents a later department/group move from retroactively
    # rewriting historical compliance numbers (mirrors ArticleReadReceipt).
    operator_department_snapshot = Column(String, nullable=True)


class VideoInstruction(Base):
    __tablename__ = "video_instructions"
    # AUTOINCREMENT so deleted ids are never reused; audit_logs references rows by item_id
    __table_args__ = {"sqlite_autoincrement": True}

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String, index=True, nullable=False)
    video_url = Column(String, nullable=False)
    category = Column(String)
    target_department = Column(String, default="All", index=True)
    created_at = Column(DateTime, default=get_tbilisi_time)
    views_count = Column(Integer, default=0)
    tags = Column(String, nullable=True)
    is_archived = Column(Boolean, default=False, index=True)


class Team(Base):
    """Team Statistics foundation — groups users for the upcoming team-level
    compliance/analytics views. Distinct from the free-text `users.department`
    field, which is unstructured and not queryable as a hierarchy."""
    __tablename__ = "teams"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, unique=True, nullable=False)
    created_at = Column(DateTime, default=get_tbilisi_time)

    members = relationship("User", back_populates="team")


class Tag(Base):
    """Normalized, on-the-fly-creatable tag vocabulary. The flat comma-separated
    `articles.tags` / `video_instructions.tags` text columns remain the source
    of truth for display; TagMapping is the queryable index over them."""
    __tablename__ = "tags"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, unique=True, nullable=False, index=True)
    created_at = Column(DateTime, default=get_tbilisi_time)


class TagMapping(Base):
    """Polymorphic many-to-many link between a Tag and a tagged item
    (item_type: 'article' | 'video'), mirroring the Favorite/RequiredReading
    polymorphic pattern already used elsewhere in this schema."""
    __tablename__ = "tags_mapping"
    __table_args__ = (
        UniqueConstraint("tag_id", "item_type", "item_id", name="uq_tag_mapping_item"),
        Index("ix_tags_mapping_item", "item_type", "item_id"),
    )

    id = Column(Integer, primary_key=True, index=True)
    tag_id = Column(Integer, ForeignKey("tags.id"), nullable=False, index=True)
    item_type = Column(String, nullable=False)
    item_id = Column(Integer, nullable=False)


class Favorite(Base):
    __tablename__ = "favorites"
    __table_args__ = (
        # Data integrity: prevent duplicate favourites at the DB level.
        UniqueConstraint(
            "user_id", "item_type", "item_id", name="uq_favorite_user_item"
        ),
    )

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    item_type = Column(String, nullable=False)
    item_id = Column(Integer, nullable=False)


class Message(Base):
    __tablename__ = "messages"
    # sender_id was an unindexed FK; the "sent messages" view filters by it
    # (recipient user_id is already indexed). Name matches migrate.py.
    __table_args__ = (Index("ix_messages_sender_id", "sender_id"),)

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    sender_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    content = Column(Text, nullable=False)
    is_read = Column(Boolean, default=False)
    created_at = Column(DateTime, default=get_tbilisi_time)

    sender = relationship("User", foreign_keys=[sender_id])
    recipient = relationship("User", foreign_keys=[user_id])

    @property
    def sender_name(self) -> Optional[str]:
        return self.sender.name if self.sender else None

    @property
    def recipient_name(self) -> Optional[str]:
        return self.recipient.name if self.recipient else None


class AuditLog(Base):
    __tablename__ = "audit_logs"
    # admin_id was an unindexed FK; the audit-log view filters by it
    # (AuditLog.admin_id == user_id). Name matches migrate.py's CREATE INDEX so
    # fresh-create and existing prod DBs converge on one index.
    __table_args__ = (
        Index("ix_audit_logs_admin_id", "admin_id"),
        Index("ix_audit_logs_category_timestamp", "category", text("timestamp DESC")),
        Index("ix_audit_logs_admin_timestamp", "admin_id", text("timestamp DESC")),
        Index("ix_audit_logs_action_timestamp", "action", text("timestamp DESC")),
    )

    id = Column(Integer, primary_key=True, index=True)
    admin_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    action = Column(String, nullable=False)
    item_type = Column(String, nullable=False)
    item_id = Column(Integer, nullable=False)
    timestamp = Column(DateTime, default=get_tbilisi_time, index=True)
    # CONTENT | USER | SECURITY | SYSTEM — auto-classified by the before_insert
    # hook below from (item_type, action) when not explicitly supplied, so
    # none of the ~40 existing AuditLog(...) call sites need to change.
    category = Column(String, nullable=True, index=True)
    details = Column(Text, nullable=True)


# Action names that are security-sensitive (identity/access) regardless of
# their item_type — these override the item_type-based default below.
_AUDIT_SECURITY_ACTIONS = {
    "LOGIN", "LOGIN_SSO", "PASSWORD_CHANGE", "PASSWORD_RESET",
    "PASSWORD_RESET_REQUEST", "CREATE_USER", "UPDATE_PERMISSIONS",
}
# Actions that describe a user's own activity rather than a content/system
# change, even when item_type points at a content table (e.g. "VIEW" an article).
_AUDIT_USER_ACTIONS = {"VIEW", "MARK_READ", "SEND_MESSAGE"}
_AUDIT_CATEGORY_BY_ITEM_TYPE = {
    "news": "CONTENT",
    "category": "CONTENT",
    "article": "CONTENT",
    "video": "CONTENT",
    "feedback": "CONTENT",
    "required_reading": "CONTENT",
    "user": "USER",
    "readings": "SYSTEM",
    "file": "SYSTEM",
    "system": "SYSTEM",
    "team_stats": "SYSTEM",
}


def classify_audit_category(item_type: str, action: str) -> str:
    """Maps (item_type, action) to one of CONTENT/USER/SECURITY/SYSTEM."""
    if action in _AUDIT_SECURITY_ACTIONS or action.startswith("UPDATE_STATUS_TO_"):
        return "SECURITY"
    if action in _AUDIT_USER_ACTIONS:
        return "USER"
    return _AUDIT_CATEGORY_BY_ITEM_TYPE.get(item_type, "SYSTEM")


@event.listens_for(AuditLog, "before_insert")
def _auto_classify_audit_log(mapper, connection, target: "AuditLog") -> None:
    """Fires on every ORM-level insert (db.add()+commit()). Bulk operations
    (bulk_insert_mappings) bypass mapper events and must set category explicitly
    — none of the current call sites use the bulk path for audit rows."""
    if not target.category:
        target.category = classify_audit_category(target.item_type, target.action)


class ArticleHistory(Base):
    __tablename__ = "article_history"

    id = Column(Integer, primary_key=True, index=True)
    article_id = Column(Integer, ForeignKey("articles.id"), nullable=False, index=True)
    title = Column(String, nullable=False)
    content = Column(Text, nullable=False)
    updated_at = Column(DateTime, default=get_tbilisi_time)
    updated_by = Column(Integer, ForeignKey("users.id"), nullable=False)
    # The article.version value that THIS snapshot represents (i.e. the version
    # number the article carried *before* the edit that created this row). Lets
    # the UI label revisions stably ("ვერსია 4") instead of by list index.
    # nullable: pre-existing rows predate the column; UI falls back to list index.
    version_id = Column(Integer, nullable=True, index=True)


class ArticleReadReceipt(Base):
    __tablename__ = "article_read_receipts"
    __table_args__ = (
        UniqueConstraint(
            "article_id", "article_version", "operator_id", name="uq_article_read_receipt_version_operator"
        ),
        Index("ix_article_read_receipts_article_version", "article_id", "article_version"),
        Index("ix_article_read_receipts_article_operator", "article_id", "operator_id"),
        Index("idx_receipts_perf_lookup", "article_id", "article_version", "read_at"),
        Index("idx_receipts_retention_date", "read_at"),
    )

    id = Column(Integer, primary_key=True, index=True)
    article_id = Column(Integer, ForeignKey("articles.id", ondelete="SET NULL"), nullable=True)
    article_title_snapshot = Column(String, nullable=False)
    article_version = Column(Integer, nullable=False)
    operator_id = Column(Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    operator_name_snapshot = Column(String, nullable=False)
    operator_email_snapshot = Column(String, nullable=False)
    operator_department_snapshot = Column(String, nullable=True)
    read_at = Column(DateTime, default=get_tbilisi_time, nullable=False)

    article = relationship("Article")
    operator = relationship("User")


class SearchLog(Base):
    __tablename__ = "search_logs"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    search_term = Column(String, index=True, nullable=False)
    timestamp = Column(DateTime, default=get_tbilisi_time)
    has_results = Column(Boolean, default=True)
    # Exact result count (Task 1's "search_history.results_found"). Kept
    # alongside has_results rather than replacing it — existing call sites
    # that only know boolean has_results keep working unchanged.
    results_found = Column(Integer, nullable=True)


class UserNote(Base):
    __tablename__ = "user_notes"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    article_id = Column(Integer, ForeignKey("articles.id"), nullable=False, index=True)
    content = Column(Text, nullable=False)
    created_at = Column(DateTime, default=get_tbilisi_time)
    updated_at = Column(DateTime, default=get_tbilisi_time, onupdate=get_tbilisi_time)


class KnowledgeFeedback(Base):
    """Operator-submitted issue reports on KB articles ("ხარვეზის რეპორტი").
    Referenced by main.py for /articles/{id}/feedback and /admin/feedback.
    Status: 'open' | 'resolved' | 'rejected'.
    """
    __tablename__ = "knowledge_feedback"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    article_id = Column(Integer, ForeignKey("articles.id"), nullable=False, index=True)
    message = Column(Text, nullable=False)
    status = Column(String, default="open", index=True)
    created_at = Column(DateTime, default=get_tbilisi_time)
    resolved_at = Column(DateTime, nullable=True)
    resolved_by = Column(Integer, ForeignKey("users.id"), nullable=True)


class Role(Base):
    __tablename__ = "roles"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, unique=True, index=True, nullable=False)
    description = Column(String, nullable=True)

    permissions = relationship("Permission", secondary="role_permissions", back_populates="roles")


class Permission(Base):
    __tablename__ = "permissions"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, unique=True, index=True, nullable=False)
    description = Column(String, nullable=True)

    roles = relationship("Role", secondary="role_permissions", back_populates="permissions")


class RolePermission(Base):
    __tablename__ = "role_permissions"

    role_id = Column(Integer, ForeignKey("roles.id", ondelete="CASCADE"), primary_key=True)
    permission_id = Column(Integer, ForeignKey("permissions.id", ondelete="CASCADE"), primary_key=True)
