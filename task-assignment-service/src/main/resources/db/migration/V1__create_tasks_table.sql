CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sector          VARCHAR(20) NOT NULL,
    alert_type      VARCHAR(30) NOT NULL,
    assigned_unit   VARCHAR(50) NOT NULL,
    priority        VARCHAR(10) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    correlation_id  VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

ALTER TABLE tasks ADD CONSTRAINT chk_task_status
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED'));

ALTER TABLE tasks ADD CONSTRAINT chk_task_priority
    CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'));

CREATE INDEX idx_task_status ON tasks(status);
CREATE INDEX idx_task_sector ON tasks(sector);

