# Magti Call Center Portal — Developer Handoff Guide

Welcome to the Magti Call Center Portal! This repository contains a fully functional, lightweight, secure, and performant knowledge management and compliance tracking system designed for call center environments.

This guide provides a comprehensive overview of the architecture, data models, Role-Based Access Control (RBAC) rules, and local setup steps required for a seamless onboarding experience.

---

## 1. Architectural Overview

The portal utilizes a lightweight, modern, single-process stack optimized for fast responses, low resource overhead, and minimal moving parts:

### Backend
*   **FastAPI**: Serves as the asynchronous Python web framework for all RESTful API endpoints and static page routing.
*   **SQLAlchemy ORM**: Handles database queries, schemas, and relationships. It uses SQLite for local development and PostgreSQL compatibility in production.
*   **Server-Sent Events (SSE)**: Powered by an in-memory `EventBroker` in `main.py`, providing real-time notifications (e.g., emergency broadcasts, new articles, and announcements) directly to operator browser tabs without polling overhead.
*   **TTL Search & Category Caching**: An in-memory cache layer (`InMemoryTTLCache` in `main.py`) protects the database from heavy query loops, caching global search and categories with a 60-second Time-To-Live.

### Frontend
*   **Single-File Architecture**: The entire user interface is housed in `base-layout.html`. This keeps deployment trivial and eliminates frontend bundlers or complex compile pipelines.
*   **Tailwind CSS & Chart.js**: Handles visual presentation and compliance analytics widgets.
*   **Vanilla JavaScript**: Drives all logic, including single-page application (SPA) routing (`navTo`), real-time SSE listener management, local search history cache, private scratchpad notes, theme preferences, and pinned bookmark docks.

---

## 2. Core Database Models

The database contains the following tables (defined in [models.py](file:///c:/Users/nikaa/OneDrive/Desktop/Magti%20base/models.py)):

*   **`User`**: Stores operator and administrator accounts, hashed passwords, departments, positions, roles, activation flags, and last active timestamps.
*   **`Article`**: The core Knowledge Base articles containing call center manuals, scripts, and target departments. Features scheduled publishing, draft modes, version numbers, and content verification timestamps.
*   **`ArticleHistory`**: Archives previous versions of articles, enabling admins to view edit histories and restore old content with a single click.
*   **`News`**: General portal announcements targeted at specific departments or the entire organization.
*   **`Category`**: Tree-like parent/child category taxonomy for classifying articles.
*   **`RequiredReading`**: Represents a compliance reading task assigned to a specific department or "All" users with a set due date.
*   **`ReadStatus`**: Tracks which operator has read which compliance task, recording exact timestamps.
*   **`VideoInstruction`**: Embeddable links for procedural video training with view trackers.
*   **`Favorite`**: Bookmarks linking users to their starred articles, news, or videos.
*   **`Message`**: Departmental supervisor-to-operator messaging subsystem.
*   **`SearchLog`**: Tracks query strings entered by employees for dashboard analytics.
*   **`AuditLog`**: Attributable logging of all administrative actions (login, content creation, uploads, deactivations, data exports).
*   **`KnowledgeFeedback`**: User-submitted issue/typo reports for specific articles.
*   **`UserNote`**: Private, persistent operator scratchpad annotations per article.

---

## 3. Role-Based Access Control (RBAC) System

The application enforces strict security checks on every request, verifying the user's role against live database records rather than relying solely on JWT payload claims (preventing stale token privilege escalation).

The 4 canonical roles are:

| Role | Target Audience | Key Privileges |
| :--- | :--- | :--- |
| **`admin`** | System Administrators | Complete system access. Manages users (activate/deactivate, change roles), reviews system audit logs, exports readings to CSV, and edits portal configurations. |
| **`content_admin`** | Content Managers / KB Authors | Creates and modifies articles (draft, publish, schedule), manages categories, uploads attachments, posts news announcements, creates required readings, publishes videos, and broadcasts emergency banners. |
| **`manager`** | Call Center Supervisors | Views departmental compliance statistics, monitors operator reading completion rates, and sends announcements/messages to operators within their own department. |
| **`operator`** | Call Center Agents | Standard consumer access. Views KB articles, reads announcements, watches training videos, signs off on required readings, bookmarks items, saves personal notes, and reports typos/issues. |

---

## 4. Local Setup & Execution Guide

Follow these steps to set up and run the portal locally:

### Step 1: Install Dependencies
Create a virtual environment and install the required Python libraries listed in `requirements.txt`:
```bash
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
```

### Step 2: Configure Environment Variables
Copy `.env.example` to `.env` in the root folder:
```bash
cp .env.example .env
```
Inside `.env`, configure the following:
*   `SECRET_KEY`: Set a secure random string for JWT token generation.
*   `DATABASE_URL`: Defaults to local SQLite (`sqlite:///./magti_portal.db`). Leave this as is for local development.

### Step 3: Initialize and Seed the Database
Run the seed script to automatically create database tables, load hierarchical categories, create mock KB articles, announcements, and default accounts:
```bash
python seed.py
```

### Step 4: Run the Development Server
Launch the FastAPI server using `uvicorn`:
```bash
uvicorn main:app --reload --port 8000
```
Open [http://127.0.0.1:8000/](http://127.0.0.1:8000/) in your browser.

### Step 5 (Optional): Rebuilding CSS
The compiled stylesheet (`static/css/custom-styles.css`) is committed, so the app
runs without a CSS build — you only need this when editing styles. The standalone
Tailwind CLI binary is **not** committed to the repo. Download it from the
[Tailwind CSS releases](https://github.com/tailwindlabs/tailwindcss/releases)
(e.g. `tailwindcss.exe` on Windows), place it in the repo root, then run:
```bash
./tailwindcss.exe -i static/css/input.css -o static/css/custom-styles.css --minify
```

---

## 5. Testing and Mock Accounts

For seamless local testing, standard BCrypt password hashing is bypassed for a set of mock Magti Active Directory accounts (defined in `security.py`). You can log in with any password for the following emails:

*   **System Admin**: `admin@magti.ge`
*   **Content Admin**: `content@magti.ge`
*   **Supervisor/Manager**: `manager@magti.ge`
*   **Operators (Agents)**: `nino@magti.ge`, `tech@magti.ge`, `info@magti.ge`
