CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE vessels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    imo_number VARCHAR(20) UNIQUE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ARRIVING',
    berth VARCHAR(10),
    eta TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE vessels ADD CONSTRAINT chk_vessel_status
    CHECK (status IN ('ARRIVING', 'DOCKED', 'DEPARTING', 'DEPARTED'));

CREATE INDEX idx_vessel_status ON vessels(status);
