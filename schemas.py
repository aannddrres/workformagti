"""Pydantic schemas for request validation and response serialization.

These schemas enforce typing, validation logic, and serialization configurations
for all FastAPI route endpoints.
"""

import re
from datetime import datetime
from typing import Optional
from pydantic import BaseModel, ConfigDict, EmailStr, field_validator


class UserBase(BaseModel):
    """Base schema properties for a portal User account."""
    email: EmailStr
    name: str
    department: Optional[str] = None
    position: Optional[str] = None
    phone: Optional[str] = None
    role: str = "operator" # 'operator', 'manager', 'admin', 'content_admin'


class UserCreate(UserBase):
    """Request schema for creating a new User account with a password."""
    password: str


class UserResponse(UserBase):
    """Response schema containing serialized profile data for a User."""
    id: int
    is_active: bool
    last_active: Optional[datetime] = None
    read_count: Optional[int] = None
    required_count: Optional[int] = None
    progress_percentage: Optional[int] = None

    model_config = ConfigDict(from_attributes=True)


class UserStatusUpdate(BaseModel):
    """Request schema for enabling or disabling a user account."""
    is_active: bool


class ForgotPasswordRequest(BaseModel):
    email: EmailStr

class Token(BaseModel):
    """Response schema containing the JWT OAuth2 access token details."""
    access_token: str
    token_type: str


class LoginRequest(BaseModel):
    """Request schema containing login credentials."""
    email: EmailStr
    password: str


class NewsBase(BaseModel):
    """Base schema properties for a portal announcement or News item."""
    title: str
    content: str
    target_department: str = "All"
    # Centralised file (PDF/image) per spec; optional.
    attachment_url: Optional[str] = None


class NewsCreate(NewsBase):
    """Request schema for creating a News announcement."""
    pass


class NewsResponse(NewsBase):
    """Response schema containing serialized News announcement details."""
    id: int
    created_at: datetime
    version: int = 1

    model_config = ConfigDict(from_attributes=True)


class NewsSummaryResponse(BaseModel):
    """Response schema for news lists, excluding the heavy content field."""
    id: int
    title: str
    target_department: str
    attachment_url: Optional[str] = None
    created_at: datetime
    version: int = 1
    model_config = ConfigDict(from_attributes=True)

class NewsHistoryResponse(BaseModel):
    """Response schema for a single News revision entry (mirrors ArticleHistory)."""
    id: int
    title: str
    content: str
    attachment_url: Optional[str] = None
    updated_at: datetime
    author_name: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class CategoryBase(BaseModel):
    """Base schema properties for a hierarchical Category."""
    name: str
    parent_id: Optional[int] = None
    slug: Optional[str] = None
    icon: Optional[str] = None
    pastel_color_class: Optional[str] = None
    is_active: bool = True


class CategoryCreate(CategoryBase):
    """Request schema for creating a Category."""
    pass


class CategoryResponse(CategoryBase):
    """Response schema containing Category details."""
    id: int

    model_config = ConfigDict(from_attributes=True)


class ArticleBase(BaseModel):
    """Base schema properties for a Knowledge Base article.

    Tracks status configurations, metadata, target departments, and staleness details.
    """
    title: str
    content: str
    category_id: int
    tags: Optional[str] = None
    target_department: str = "All"
    status: str = "published"
    published_at: Optional[datetime] = None
    attachment_url: Optional[str] = None
    author_id: Optional[int] = None
    last_verified_at: Optional[datetime] = None
    audience_profile: str = "all"  # 'info' | 'tech' | 'all'


class ArticleSummaryResponse(BaseModel):
    """Response schema containing summary details of an article (excludes content)."""
    id: int
    title: str
    category_id: int
    tags: Optional[str] = None
    target_department: str
    status: str
    created_at: datetime
    read_time: int = 1
    audience_profile: str = "all"

    model_config = ConfigDict(from_attributes=True)

class ArticleCreate(ArticleBase):
    """Request schema for creating a new Knowledge Base article."""
    pass


class ArticleResponse(ArticleBase):
    """Response schema containing serialized Knowledge Base article details."""
    id: int
    created_at: datetime
    updated_at: datetime
    version: int
    read_time: int = 1

    model_config = ConfigDict(from_attributes=True)


class RequiredReadingBase(BaseModel):
    """Base schema properties for a compliance required reading task."""
    item_type: str
    item_id: int
    target_department: str = "All"
    due_date: datetime
    priority: str = "normal"


class RequiredReadingResponse(RequiredReadingBase):
    """Response schema containing required reading assignment details."""
    id: int
    model_config = ConfigDict(from_attributes=True)


class ReadStatusBase(BaseModel):
    """Base schema properties for tracking reading task completions."""
    status: str = "unread"
    read_at: Optional[datetime] = None


class ReadStatusResponse(ReadStatusBase):
    """Response schema representing a user's reading status on a task."""
    id: int
    user_id: int
    required_reading_id: int
    model_config = ConfigDict(from_attributes=True)


class MyReadingResponse(BaseModel):
    """Response schema detailing an operator's reading task list item."""
    reading: RequiredReadingResponse
    status: str
    read_at: Optional[datetime] = None
    is_overdue: bool = False
    item_title: Optional[str] = None
    item_content: Optional[str] = None


class ComplianceStatsResponse(BaseModel):
    """Response schema containing aggregate compliance dashboard stats."""
    read_percentage: float
    unread_percentage: float
    top_articles: list[ArticleResponse]


class VideoInstructionBase(BaseModel):
    """Base schema properties for video tutorials."""
    title: str
    video_url: str
    category: Optional[str] = None
    target_department: str = "All"


class VideoInstructionCreate(VideoInstructionBase):
    """Request schema for creating a video instruction tutorial."""
    pass


class VideoInstructionResponse(VideoInstructionBase):
    """Response schema containing video details and total view count."""
    id: int
    created_at: datetime
    views_count: int
    model_config = ConfigDict(from_attributes=True)


class FavoriteBase(BaseModel):
    """Base schema properties for bookmarks/favorites."""
    item_type: str
    item_id: int


class FavoriteCreate(FavoriteBase):
    """Request schema for creating a bookmarked item."""
    pass


class FavoriteResponse(FavoriteBase):
    """Response schema representing a bookmarked item."""
    id: int
    user_id: int
    item_title: Optional[str] = None
    model_config = ConfigDict(from_attributes=True)


class GlobalSearchResponse(BaseModel):
    """Unified search response representing global search hits across types."""
    articles: list[ArticleSummaryResponse]
    news: list[NewsSummaryResponse]
    videos: list[VideoInstructionResponse]


class MessageCreate(BaseModel):
    """Request schema containing private message content to send."""
    user_id: int
    content: str


class MessageResponse(BaseModel):
    """Response schema representing a private message and read state."""
    id: int
    user_id: int
    sender_id: Optional[int] = None
    content: str
    is_read: bool
    created_at: datetime
    model_config = ConfigDict(from_attributes=True)


class UserSelfUpdate(BaseModel):
    """Request schema for operators to update their own profile fields."""
    name: str
    phone: Optional[str] = None
    position: Optional[str] = None


class UserAdminUpdate(BaseModel):
    """Request schema for administrators to update roles and departments."""
    role: str
    department: Optional[str] = None
    phone: Optional[str] = None
    position: Optional[str] = None

    @field_validator('role')
    @classmethod
    def validate_role(cls, v: str) -> str:
        """Enforce validation rules on user role changes."""
        allowed = {"operator", "manager", "content_admin", "admin"}
        if v not in allowed:
            raise ValueError(f"Invalid role. Must be one of: {', '.join(sorted(allowed))}")
        return v


class UserCreateAdmin(BaseModel):
    """Request schema for admins to create a new user account.
    Password is validated against the central policy in security.validate_password_policy().
    """
    email: EmailStr
    name: str
    department: Optional[str] = None
    position: Optional[str] = None
    phone: Optional[str] = None
    role: str = "operator"
    password: str

    @field_validator('role')
    @classmethod
    def validate_role(cls, v: str) -> str:
        allowed = {"operator", "manager", "content_admin", "admin"}
        if v not in allowed:
            raise ValueError(f"Invalid role. Must be one of: {', '.join(sorted(allowed))}")
        return v


class PasswordChangeRequest(BaseModel):
    """Request schema for users to change their own password."""
    current_password: str
    new_password: str


class FeedbackStatusUpdate(BaseModel):
    """Request schema for admins to update a feedback report's status."""
    status: str  # 'open' | 'resolved' | 'rejected'

    @field_validator('status')
    @classmethod
    def validate_status(cls, v: str) -> str:
        allowed = {"open", "resolved", "rejected"}
        if v not in allowed:
            raise ValueError(f"Invalid feedback status. Must be one of: {', '.join(sorted(allowed))}")
        return v


class KpiResponse(BaseModel):
    """Response schema containing main administrator dashboard counts."""
    users: int
    articles: int
    required_readings: int
    videos: int


class AuditLogBase(BaseModel):
    """Base schema properties for auditing history logs."""
    action: str
    item_type: str
    item_id: int


class AuditLogResponse(AuditLogBase):
    """Response schema representing an administrative action log entry."""
    id: int
    admin_id: int
    timestamp: datetime
    model_config = ConfigDict(from_attributes=True)


class PopularSearchResponse(BaseModel):
    """Response schema representing a frequently searched term."""
    search_term: str
    count: int


class TeamMemberStats(BaseModel):
    """Response schema containing reading statistics for a team member."""
    user_id: int
    user_name: str
    read_count: int
    required_count: int
    percentage: str


class TeamStatsResponse(BaseModel):
    """Response schema representing supervisor/manager department analytics."""
    department: str
    members: list[TeamMemberStats]


class BroadcastRequest(BaseModel):
    """Request schema containing broadcast message text."""
    message: str


class KnowledgeFeedbackCreate(BaseModel):
    """Request schema for operators reporting article issues."""
    message: str


class KnowledgeFeedbackResponse(BaseModel):
    """Response schema detailing reported feedback, reporter, and target article."""
    id: int
    user_id: int
    article_id: int
    message: str
    status: str
    created_at: datetime
    user_name: Optional[str] = None
    article_title: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class UserNoteCreate(BaseModel):
    """Request schema for operator private annotations."""
    content: str


class UserNoteResponse(BaseModel):
    """Response schema detailing an operator's private annotation."""
    id: int
    user_id: int
    article_id: int
    content: str

    model_config = ConfigDict(from_attributes=True)