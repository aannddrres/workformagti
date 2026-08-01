-- Mirrors models.py's User (models.py:22-55), with one deliberate
-- omission: last_categories_viewed_at is dropped (confirmed dead --
-- no router/template/script anywhere reads or writes it -- decision
-- 2026-07-30, see domain/User.java's javadoc).
--
-- permissions: Oracle 19c has no native JSON column type (that's 21c+), so
-- this uses the pre-21c CLOB + CHECK (... IS JSON) pattern instead --
-- structurally validated JSON, read/written via domain/PermissionsConverter.
CREATE TABLE users (
    id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email               VARCHAR2(255 CHAR) NOT NULL,
    name                VARCHAR2(200 CHAR) NOT NULL,
    department          VARCHAR2(200 CHAR),
    position            VARCHAR2(200 CHAR),
    phone               VARCHAR2(30 CHAR),
    role                VARCHAR2(30 CHAR) DEFAULT 'operator',
    is_active           NUMBER(1) DEFAULT 1,
    last_active         TIMESTAMP(6),
    hashed_password     VARCHAR2(255 CHAR),
    permissions         CLOB,
    team_id             NUMBER,
    manager_id          NUMBER,
    last_news_viewed_at TIMESTAMP(6),
    card_style          VARCHAR2(50 CHAR) DEFAULT 'corporate',
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT fk_users_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_users_manager FOREIGN KEY (manager_id) REFERENCES users (id),
    CONSTRAINT ck_users_permissions_json CHECK (permissions IS JSON)
);

CREATE INDEX ix_users_department ON users (department);
-- Composite index name matches models.py's ix_users_department_active
-- (models.py:26) so the two schemas stay cross-referenceable.
CREATE INDEX ix_users_department_active ON users (department, is_active);
CREATE INDEX ix_users_role ON users (role);
CREATE INDEX ix_users_team_id ON users (team_id);
CREATE INDEX ix_users_manager_id ON users (manager_id);
