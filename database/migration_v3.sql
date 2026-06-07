-- 小说写作辅助系统数据库迁移 v3.0
-- 新增功能：世界观模块 + WebDAV 同步支持

-- ============================================
-- 世界观条目表
-- ============================================
CREATE TABLE worldview_entries (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL,  -- geography, history, culture, magic_tech, races, politics, religion, other
    content TEXT,
    entry_image VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 世界观标签关联表
-- ============================================
CREATE TABLE worldview_entry_tags (
    id SERIAL PRIMARY KEY,
    worldview_entry_id INTEGER REFERENCES worldview_entries(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(worldview_entry_id, tag_id)
);

-- ============================================
-- 世界观关联人物表
-- ============================================
CREATE TABLE worldview_entry_characters (
    id SERIAL PRIMARY KEY,
    worldview_entry_id INTEGER REFERENCES worldview_entries(id) ON DELETE CASCADE,
    character_id INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    UNIQUE(worldview_entry_id, character_id)
);

-- ============================================
-- 世界观关联场景表
-- ============================================
CREATE TABLE worldview_entry_scenes (
    id SERIAL PRIMARY KEY,
    worldview_entry_id INTEGER REFERENCES worldview_entries(id) ON DELETE CASCADE,
    scene_id INTEGER REFERENCES scenes(id) ON DELETE CASCADE,
    UNIQUE(worldview_entry_id, scene_id)
);

-- ============================================
-- 世界观关联地图位置表
-- ============================================
CREATE TABLE worldview_entry_map_locations (
    id SERIAL PRIMARY KEY,
    worldview_entry_id INTEGER REFERENCES worldview_entries(id) ON DELETE CASCADE,
    map_location_id INTEGER REFERENCES map_locations(id) ON DELETE CASCADE,
    UNIQUE(worldview_entry_id, map_location_id)
);

-- ============================================
-- 索引
-- ============================================
CREATE INDEX idx_worldview_novel ON worldview_entries(novel_id);
CREATE INDEX idx_worldview_category ON worldview_entries(category);
CREATE INDEX idx_worldview_entry_tags_entry ON worldview_entry_tags(worldview_entry_id);
CREATE INDEX idx_worldview_entry_tags_tag ON worldview_entry_tags(tag_id);
CREATE INDEX idx_worldview_entry_characters_entry ON worldview_entry_characters(worldview_entry_id);
CREATE INDEX idx_worldview_entry_characters_character ON worldview_entry_characters(character_id);
CREATE INDEX idx_worldview_entry_scenes_entry ON worldview_entry_scenes(worldview_entry_id);
CREATE INDEX idx_worldview_entry_scenes_scene ON worldview_entry_scenes(scene_id);
CREATE INDEX idx_worldview_entry_map_locations_entry ON worldview_entry_map_locations(worldview_entry_id);
CREATE INDEX idx_worldview_entry_map_locations_location ON worldview_entry_map_locations(map_location_id);

-- ============================================
-- WebDAV 同步支持 - novels 表新增列
-- ============================================
ALTER TABLE novels ADD COLUMN webdav_server_url VARCHAR(500);
ALTER TABLE novels ADD COLUMN webdav_username VARCHAR(100);
ALTER TABLE novels ADD COLUMN webdav_password VARCHAR(500);
ALTER TABLE novels ADD COLUMN webdav_auto_sync BOOLEAN DEFAULT FALSE;
ALTER TABLE novels ADD COLUMN last_webdav_sync TIMESTAMP;
