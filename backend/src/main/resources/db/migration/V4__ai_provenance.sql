-- AI provenance for wardrobe items (debugging / analytics / model improvement).
-- These record what the CV pipeline predicted at scan time. They must NEVER be
-- used as the canonical garment type — category/sub_category remain the
-- user-confirmed truth used by styling/recommendation logic. Provenance is
-- write-once at confirm time and nullable (older items + manual adds have none).

ALTER TABLE clothing_items
    ADD COLUMN IF NOT EXISTS ai_predicted_category     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS ai_predicted_sub_category VARCHAR(100),
    ADD COLUMN IF NOT EXISTS ai_subtype_confidence     REAL,
    ADD COLUMN IF NOT EXISTS ai_model_source           VARCHAR(120);
