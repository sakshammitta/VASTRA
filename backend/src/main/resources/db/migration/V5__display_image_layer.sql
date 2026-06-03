-- Display layer, separate from the truth layer.
--   Truth layer  = r2_image_key (the real extracted crop) + detected/confirmed
--                  attributes (category, sub_category, colors, ai_* provenance).
--   Display layer = the image actually shown in the app, which may be cleaner
--                   than the raw crop (a confirmed web product match or an
--                   AI-generated clean render).
--
-- display_image_key is NULL until a cleaner display image is confirmed; when
-- NULL the app falls back to r2_image_key (the crop). display_image_source
-- defaults to CROP so existing behaviour is unchanged. A non-CROP source is
-- only ever set after explicit user confirmation in the review flow.

ALTER TABLE clothing_items
    ADD COLUMN IF NOT EXISTS display_image_key    VARCHAR(512),
    ADD COLUMN IF NOT EXISTS display_image_source VARCHAR(16) NOT NULL DEFAULT 'CROP';
