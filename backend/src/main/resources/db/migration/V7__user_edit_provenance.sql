-- User-edit provenance for wardrobe items.
-- When a user corrects a saved item's category/sub_category (or other identity
-- fields), that correction becomes the canonical truth. user_edited flags such
-- rows so styling/recommendation logic and any future AI "refresh" must treat
-- the user value as authoritative and NEVER silently overwrite it.
--
-- Rule: User edit > AI suggestion. The ai_predicted_* columns (V4) keep the
-- original AI guess for analytics / model-error evaluation; the live
-- category/sub_category columns hold the user-confirmed truth.

ALTER TABLE clothing_items
    ADD COLUMN IF NOT EXISTS user_edited     BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS user_edited_at  TIMESTAMPTZ;
