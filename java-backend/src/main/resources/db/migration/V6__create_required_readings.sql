-- Mirrors models.py's RequiredReading (models.py:201-214). item_type/item_id
-- is a polymorphic soft-reference (article or news today), not a real FK.
CREATE TABLE required_readings (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_type         VARCHAR2(20 CHAR) NOT NULL,
    item_id           NUMBER NOT NULL,
    target_department VARCHAR2(200 CHAR) DEFAULT 'All',
    due_date          TIMESTAMP(6) NOT NULL,
    priority          VARCHAR2(20 CHAR) DEFAULT 'normal'
);

-- Matches models.py's ix_required_readings_department (models.py:205).
CREATE INDEX ix_required_readings_department ON required_readings (target_department);
