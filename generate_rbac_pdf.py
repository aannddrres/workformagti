"""One-shot script: generates the Magti Portal RBAC access matrix as a PDF."""
import os
from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.lib.units import mm
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak,
)
from reportlab.lib.styles import ParagraphStyle

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rbac_matrix.pdf")

MAGTI_RED = colors.HexColor("#CC0000")
LIGHT_GRAY = colors.HexColor("#F5F5F5")
GREEN = colors.HexColor("#1D9E75")
AMBER = colors.HexColor("#BA7517")
GRAY_TEXT = colors.HexColor("#888888")

Y = "✓"
N = "—"

style_title = ParagraphStyle("title", fontSize=18, fontName="Helvetica-Bold",
                             spaceAfter=4, textColor=MAGTI_RED)
style_subtitle = ParagraphStyle("sub", fontSize=9, fontName="Helvetica",
                                spaceAfter=14, textColor=colors.gray)
style_section = ParagraphStyle("sec", fontSize=13, fontName="Helvetica-Bold",
                               spaceBefore=16, spaceAfter=6, textColor=MAGTI_RED)
style_body = ParagraphStyle("body", fontSize=9, fontName="Helvetica",
                            leading=12, spaceAfter=6)
style_note = ParagraphStyle("note", fontSize=8, fontName="Helvetica-Oblique",
                            leading=10, spaceAfter=10, textColor=colors.gray)
style_obs = ParagraphStyle("obs", fontSize=9, fontName="Helvetica",
                           leading=13, spaceAfter=4, bulletIndent=10,
                           leftIndent=18)


def _build_table(header_row, data_rows, col_widths=None):
    all_rows = [header_row] + data_rows
    if col_widths is None:
        col_widths = [195, 60, 60, 70, 55]
    t = Table(all_rows, colWidths=col_widths, repeatRows=1)
    base_style = [
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, 0), 8),
        ("FONTSIZE", (0, 1), (-1, -1), 8),
        ("FONTNAME", (0, 1), (-1, -1), "Helvetica"),
        ("BACKGROUND", (0, 0), (-1, 0), MAGTI_RED),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ALIGN", (1, 0), (-1, -1), "CENTER"),
        ("ALIGN", (0, 0), (0, -1), "LEFT"),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#CCCCCC")),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
    ]
    for i in range(1, len(all_rows)):
        if i % 2 == 0:
            base_style.append(("BACKGROUND", (0, i), (-1, i), LIGHT_GRAY))
        for j in range(1, len(all_rows[i])):
            cell = str(all_rows[i][j])
            if cell == Y:
                base_style.append(("TEXTCOLOR", (j, i), (j, i), GREEN))
                base_style.append(("FONTNAME", (j, i), (j, i), "Helvetica-Bold"))
            elif cell == N:
                base_style.append(("TEXTCOLOR", (j, i), (j, i), GRAY_TEXT))
            else:
                base_style.append(("TEXTCOLOR", (j, i), (j, i), AMBER))
                base_style.append(("FONTSIZE", (j, i), (j, i), 7))
    t.setStyle(TableStyle(base_style))
    return t


def build():
    doc = SimpleDocTemplate(OUT, pagesize=A4,
                            topMargin=18*mm, bottomMargin=15*mm,
                            leftMargin=15*mm, rightMargin=15*mm)
    story = []

    story.append(Paragraph("Magti Portal — Role Access Matrix", style_title))
    story.append(Paragraph(
        "Complete list of who can do what. Four roles: operator, manager, content_admin, admin.",
        style_subtitle))

    story.append(Paragraph(
        '<font color="#1D9E75">✓</font> = Full access &nbsp;&nbsp;&nbsp; '
        '<font color="#BA7517">text</font> = Conditional / limited &nbsp;&nbsp;&nbsp; '
        '<font color="#888888">—</font> = No access',
        style_body))
    story.append(Spacer(1, 6))

    # 1. Auth Dependencies
    story.append(Paragraph("1. Auth guards (security.py)", style_section))
    story.append(Paragraph(
        "Every endpoint uses one of these guards. They decide which roles can enter.",
        style_body))
    story.append(_build_table(
        ["Guard name", "operator", "manager", "content\nadmin", "admin"],
        [
            ["get_current_user\n(any logged-in user)", Y, Y, Y, Y],
            ["get_current_manager_user", N, Y, N, Y],
            ["get_current_admin_user", N, N, Y, Y],
            ["get_current_system_admin_user", N, N, N, Y],
            ["require_permission(perm)", "if in list", "if in list", "if in list", "always"],
        ],
    ))

    # 2. User Management
    story.append(Paragraph("2. User management", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["See own profile", Y, Y, Y, Y],
            ["Edit own name / phone", Y, Y, Y, Y],
            ["Change own password", Y, Y, Y, Y],
            ["List all users", N, N, N, Y],
            ["Create a new user", N, N, N, Y],
            ["Edit user role / dept / position", N, N, N, Y],
            ["Activate or deactivate a user", N, N, N, Y],
            ["Reset another user's password", N, N, N, Y],
            ["Set user permissions", N, N, N, Y],
            ["List group leaders", N, N, N, Y],
        ],
    ))

    # 3. News
    story.append(Paragraph("3. News / announcements", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["Read news list", "own dept", "own dept", Y, Y],
            ["Read single news item", "own dept", "own dept", Y, Y],
            ["Create news", N, N, Y, Y],
            ["Edit news", N, N, Y, Y],
            ["Delete news", N, N, Y, Y],
            ["See edit history", N, N, Y, Y],
            ["Restore old version", N, N, Y, Y],
        ],
    ))

    # 4. Articles
    story.append(Paragraph("4. Articles (knowledge base)", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["Browse articles", "published\nown dept", "published\nown dept", "all statuses", "all statuses"],
            ["Read single article", "published\nown dept", "published\nown dept", Y, Y],
            ["Create article", N, N, Y, Y],
            ["Edit article", N, N, Y, Y],
            ["Delete article", N, N, Y, Y],
            ["Archive / unarchive", N, N, "needs perm", "always"],
            ["Mark as verified", N, N, Y, Y],
            ["See edit history", N, N, Y, Y],
            ["Restore old version", N, N, Y, Y],
            ["Log a view", Y, Y, Y, Y],
            ["See related articles", "own dept", "own dept", Y, Y],
            ["Personal notes on article", Y, Y, Y, Y],
            ["Report an issue", Y, Y, Y, Y],
            ["See stale articles list", N, N, Y, Y],
        ],
    ))

    story.append(PageBreak())

    # 5. Categories
    story.append(Paragraph("5. Categories", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["List categories", Y, Y, Y, Y],
            ["Create category", N, N, Y, Y],
            ["Edit category", N, N, Y, Y],
            ["Delete category", N, N, Y, Y],
        ],
    ))

    # 6. Videos
    story.append(Paragraph("6. Video instructions", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["List videos", "own dept\nactive only", "own dept\nactive only", Y, Y],
            ["Log a view", Y, Y, Y, Y],
            ["Create video", N, N, Y, Y],
            ["Edit video", N, N, Y, Y],
            ["Delete video", N, N, Y, Y],
            ["Archive / unarchive", N, N, "needs perm", "always"],
        ],
    ))

    # 7. Required Readings
    story.append(Paragraph("7. Required readings (compliance)", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["See my own assignments", Y, Y, Y, Y],
            ["Mark a reading as done", Y, Y, Y, Y],
            ["Assign a new required reading", N, N, Y, Y],
            ["Look up reading by item", N, N, Y, Y],
            ["Edit a required reading", N, N, Y, Y],
            ["Delete a required reading", N, N, Y, Y],
        ],
    ))

    # 8. Statistics & Exports
    story.append(Paragraph("8. Statistics, dashboards, and exports", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["Global compliance stats\n(read / unread ratio)", N, N, Y, Y],
            ["Per-user reading progress\n(personal data)", N, N, N, Y],
            ["KPI counts\n(users, articles, readings, videos)", N, N, Y, Y],
            ["Daily login activity", N, N, Y, Y],
            ["Popular search terms", N, N, Y, Y],
            ["Failed search terms", N, N, Y, Y],
            ["Team stats (manager view)", N, "own dept", N, "all depts"],
            ["Department stats dashboard", N, Y, N, Y],
            ["Team drill-down by team ID", N, N, Y, Y],
            ["Critical operators list", N, N, Y, Y],
            ["Group user completion stats", N, N, Y, Y],
            ["CSV export (personal data)", N, N, N, Y],
            ["XLSX export (personal data)", N, N, N, Y],
            ["PDF export: readings", N, "needs perm", "needs perm", "always"],
            ["PDF export: team stats", N, "needs perm", "needs perm", "always"],
        ],
    ))
    story.append(Paragraph(
        "Note: CSV and XLSX exports need admin role (they contain personal data). "
        "PDF exports use the reports.export permission — manager gets it by default, "
        "content_admin does not unless added manually.",
        style_note))

    story.append(PageBreak())

    # 9. Messaging
    story.append(Paragraph("9. Messaging and notifications", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["Read own inbox", Y, Y, Y, Y],
            ["Send a message", N, "own dept", N, "anyone"],
            ["Mark message as read", Y, Y, Y, Y],
            ["Delete a message", Y, Y, Y, Y],
            ["Broadcast to department (SSE)", N, N, Y, Y],
            ["Send compliance nudge", N, Y, Y, Y],
            ["Notification summary", Y, Y, Y, Y],
        ],
    ))

    # 10. Other
    story.append(Paragraph("10. Search, favorites, tags, teams, feedback, audit", style_section))
    story.append(_build_table(
        ["What", "operator", "manager", "content\nadmin", "admin"],
        [
            ["Search articles", "own dept", "own dept", "all", "all"],
            ["Global search", "own dept", "own dept", "all", "all"],
            ["Search history", Y, Y, Y, Y],
            ["Favorites (view / add / remove)", Y, Y, Y, Y],
            ["Tags list", Y, Y, Y, Y],
            ["Teams list", Y, Y, Y, Y],
            ["Create team", N, N, N, Y],
            ["View all feedback reports", N, N, Y, Y],
            ["Resolve / reject feedback", N, N, Y, Y],
            ["Audit logs", N, N, N, Y],
            ["Upload files", N, N, Y, Y],
            ["SSE event stream", Y, Y, Y, Y],
            ["Health check", "no auth", "no auth", "no auth", "no auth"],
        ],
    ))

    # 11. Permissions
    story.append(Paragraph("11. Default permissions given to each role", style_section))
    story.append(Paragraph(
        "These are set when a user is created. Admin can change them per user. "
        "The admin role always bypasses permission checks — it does not need any of these.",
        style_body))
    story.append(_build_table(
        ["Permission", "operator", "manager", "content\nadmin", "admin"],
        [
            ["articles.view", N, N, Y, Y],
            ["articles.edit", N, N, Y, Y],
            ["articles.publish", N, N, Y, Y],
            ["articles.archive", N, N, Y, Y],
            ["videos.archive", N, N, Y, Y],
            ["users.manage", N, N, N, Y],
            ["compliance.assign", N, N, Y, Y],
            ["reports.export", N, Y, N, Y],
        ],
    ))

    # Key Observations
    story.append(Spacer(1, 12))
    story.append(Paragraph("Key observations", style_section))

    observations = [
        "<b>User management is admin-only.</b> Creating, editing, activating, deactivating users, "
        "resetting passwords, and setting permissions all need the admin role. No other role can touch user accounts.",
        "<b>Content work is shared between content_admin and admin.</b> Both can create, edit, and delete "
        "articles, news, categories, and videos. Operators and managers cannot change any content.",
        "<b>Statistics are split by sensitivity.</b> General stats (KPIs, compliance ratio, search analytics) "
        "are open to content_admin + admin. Per-user progress and data exports (CSV, XLSX) are admin-only "
        "because they contain personal data. Managers see only their own department's team stats.",
        "<b>Messaging has department walls.</b> Managers can only message users in their own department. "
        "Admin can message anyone. Operators and content_admins cannot send messages at all.",
        "<b>The permission system is layered.</b> On top of the four roles, there are named permissions "
        "(like reports.export, articles.archive) stored per user. Admin always bypasses these checks. "
        "Other roles need the permission in their list. Defaults are set at creation time but can be "
        "changed by admin later.",
        "<b>Operators have the smallest footprint.</b> They can read content for their department, "
        "manage their own profile, mark readings as done, use favorites and notes, and search. "
        "Everything else is blocked.",
    ]
    for obs in observations:
        story.append(Paragraph(obs, style_obs))
        story.append(Spacer(1, 3))

    doc.build(story)
    return OUT


if __name__ == "__main__":
    path = build()
    print(f"PDF saved to: {path}")
