-- A persisted singleton; every matching request snapshots this row once.
CREATE TABLE matching_salary_settings (
    id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    tolerance_percent numeric NOT NULL CHECK (
        tolerance_percent BETWEEN 0 AND 100 AND scale(tolerance_percent) <= 2
    ),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES users(id)
);

INSERT INTO matching_salary_settings(id,tolerance_percent,version) VALUES (1,10.00,1);

COMMENT ON COLUMN matching_salary_settings.tolerance_percent IS
    'Monthly candidate expected salary shortfall allowed in percent; no upper salary cap.';
