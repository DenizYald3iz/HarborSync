ALTER TABLE vessels DROP CONSTRAINT IF EXISTS chk_vessel_status;

ALTER TABLE vessels ADD CONSTRAINT chk_vessel_status
    CHECK (status IN ('ARRIVING', 'BERTHED', 'DOCKED', 'DEPARTING', 'DEPARTED'));
