CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS style_embedding vector(512);

ALTER TABLE clothing_items
    ADD COLUMN IF NOT EXISTS fashion_clip_embedding vector(512);

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS image_embedding vector(512);
