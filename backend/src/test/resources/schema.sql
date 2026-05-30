-- H2-compatible test schema.
-- Mirrors V1 + V2 Flyway migrations with two changes:
--   1. vector(512) replaced with VARCHAR(2048) (H2 has no vector type)
--   2. Column defaults removed from PK columns (Hibernate supplies all values)

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    avatar_r2_key VARCHAR(500),
    bio TEXT,
    price_min INT NOT NULL DEFAULT 0,
    price_max INT NOT NULL DEFAULT 500,
    follower_count INT NOT NULL DEFAULT 0,
    following_count INT NOT NULL DEFAULT 0,
    post_count INT NOT NULL DEFAULT 0,
    item_count INT NOT NULL DEFAULT 0,
    style_embedding VARCHAR(2048),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_friends (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP,
    PRIMARY KEY (user_id, friend_id)
);

CREATE TABLE IF NOT EXISTS user_preferred_categories (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, category)
);

CREATE TABLE IF NOT EXISTS clothing_items (
    id UUID PRIMARY KEY,
    catalog_id VARCHAR(100),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ownership_status VARCHAR(20) NOT NULL DEFAULT 'OWNED',
    r2_image_key VARCHAR(500),
    r2_thumbnail_key VARCHAR(500),
    category VARCHAR(30) NOT NULL,
    sub_category VARCHAR(100) NOT NULL DEFAULT '',
    brand VARCHAR(100),
    purchase_platform VARCHAR(50),
    purchase_url TEXT,
    price_usd NUMERIC(10, 2),
    style_match_percent INT NOT NULL DEFAULT 0,
    fashion_clip_embedding VARCHAR(2048),
    added_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS item_tags (
    item_id UUID NOT NULL REFERENCES clothing_items(id) ON DELETE CASCADE,
    tag VARCHAR(100) NOT NULL,
    PRIMARY KEY (item_id, tag)
);

CREATE TABLE IF NOT EXISTS item_colors (
    item_id UUID NOT NULL REFERENCES clothing_items(id) ON DELETE CASCADE,
    hex_color VARCHAR(10) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (item_id, sort_order)
);

CREATE TABLE IF NOT EXISTS posts (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    r2_image_key VARCHAR(500) NOT NULL,
    caption TEXT,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    like_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    save_count INT NOT NULL DEFAULT 0,
    image_embedding VARCHAR(2048),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shoppable_tags (
    id UUID PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    item_id UUID REFERENCES clothing_items(id) ON DELETE SET NULL,
    x_norm FLOAT NOT NULL,
    y_norm FLOAT NOT NULL,
    label VARCHAR(200) NOT NULL
);

CREATE TABLE IF NOT EXISTS post_style_labels (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    label VARCHAR(100) NOT NULL,
    PRIMARY KEY (post_id, label)
);

CREATE TABLE IF NOT EXISTS post_dominant_colors (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    hex_color VARCHAR(10) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (post_id, sort_order)
);

CREATE TABLE IF NOT EXISTS post_tagged_items (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES clothing_items(id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, item_id)
);

CREATE TABLE IF NOT EXISTS post_likes (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP,
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS post_saves (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP,
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS comments (
    id UUID PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    created_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_clothing_items_owner ON clothing_items(owner_id);
CREATE INDEX IF NOT EXISTS idx_clothing_items_category ON clothing_items(category);
CREATE INDEX IF NOT EXISTS idx_posts_author ON posts(author_id);
CREATE INDEX IF NOT EXISTS idx_posts_visibility ON posts(visibility);
CREATE INDEX IF NOT EXISTS idx_posts_created ON posts(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_comments_post ON comments(post_id);
