-- Product-owner decision PO-05 / D-7: a broadcast is a durable, company-wide
-- portal announcement, not one Message row per recipient. V37 is deliberately
-- reserved for the later org tightening migration and must not be used here.
CREATE TABLE broadcasts (
    id                       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message                  CLOB NOT NULL,
    priority                 VARCHAR2(20 CHAR) NOT NULL,
    published_at             TIMESTAMP(6) NOT NULL,
    ends_at                  TIMESTAMP(6) NOT NULL,
    ended_at                 TIMESTAMP(6),
    published_by_user_id     NUMBER,
    publisher_name_snapshot  VARCHAR2(255 CHAR) NOT NULL,
    ended_by_user_id         NUMBER,
    ended_by_name_snapshot   VARCHAR2(255 CHAR),
    lock_version             NUMBER DEFAULT 0 NOT NULL,
    CONSTRAINT fk_broadcast_publisher FOREIGN KEY (published_by_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_broadcast_ended_by FOREIGN KEY (ended_by_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_broadcast_priority CHECK (priority IN ('NORMAL', 'IMPORTANT', 'CRITICAL')),
    CONSTRAINT ck_broadcast_window CHECK (ends_at > published_at),
    CONSTRAINT ck_broadcast_ended_at CHECK (ended_at IS NULL OR ended_at >= published_at)
);

-- ended_at is the first column used by the active predicate; ends_at then
-- narrows the remaining live rows. published_at supports stable history order.
CREATE INDEX ix_broadcast_active ON broadcasts (ended_at, ends_at);
CREATE INDEX ix_broadcast_published ON broadcasts (published_at);
CREATE INDEX ix_broadcast_publisher ON broadcasts (published_by_user_id);
