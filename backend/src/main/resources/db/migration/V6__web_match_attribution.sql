-- Attribution fields for web-matched and AI-rendered display images.
-- web_match_url : original product page / source URL (attribution, not the stored R2 key)
-- web_match_query : query used to find the match (debug / future search improvement)
-- Both are nullable; only set on WEB_PRODUCT items after user confirmation.

ALTER TABLE clothing_items
    ADD COLUMN IF NOT EXISTS web_match_url   VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS web_match_query VARCHAR(512);
