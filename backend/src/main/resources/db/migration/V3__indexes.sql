-- IVFFlat ANN indexes for pgvector cosine similarity search
-- lists = sqrt(expected_rows), tuned conservatively for MVP scale

CREATE INDEX IF NOT EXISTS idx_item_embedding_cosine
    ON clothing_items USING ivfflat (fashion_clip_embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE INDEX IF NOT EXISTS idx_post_embedding_cosine
    ON posts USING ivfflat (image_embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE INDEX IF NOT EXISTS idx_user_style_embedding_cosine
    ON users USING ivfflat (style_embedding vector_cosine_ops)
    WITH (lists = 50);
