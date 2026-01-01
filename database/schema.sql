-- 小说写作辅助系统数据库设计 (PostgreSQL) v2.0
-- 包含：小说管理、标签系统、图片上传、人物关系等功能

-- ============================================
-- 小说表（顶层管理）
-- ============================================
CREATE TABLE novels (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    author VARCHAR(100),
    genre VARCHAR(100),
    status VARCHAR(20) DEFAULT 'writing', -- planning, writing, completed, paused
    cover_image VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 标签表
-- ============================================
CREATE TABLE tags (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    color VARCHAR(20) DEFAULT '#3498db',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_tags_novel_name ON tags(novel_id, name);

-- ============================================
-- 图片表
-- ============================================
CREATE TABLE images (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    original_name VARCHAR(255),
    file_path VARCHAR(500) NOT NULL,
    file_size INTEGER,
    mime_type VARCHAR(100),
    image_type VARCHAR(50), -- portrait, scene, map, other
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 人物表
-- ============================================
CREATE TABLE characters (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    personality TEXT,
    appearance TEXT,
    background TEXT,
    role VARCHAR(50),
    portrait_image VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 场景表
-- ============================================
CREATE TABLE scenes (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    location VARCHAR(200),
    atmosphere TEXT,
    scene_image VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 伏笔表
-- ============================================
CREATE TABLE foreshadows (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    laid_at VARCHAR(100),
    revealed_at VARCHAR(100),
    status VARCHAR(20) DEFAULT 'pending', -- pending, revealed, abandoned
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 大纲表
-- ============================================
CREATE TABLE outlines (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    chapter_number INTEGER,
    plot_order INTEGER,
    status VARCHAR(20) DEFAULT 'planning', -- planning, writing, completed
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 时间轴事件表
-- ============================================
CREATE TABLE timeline_events (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    event_time VARCHAR(100),
    real_order INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 地图位置表
-- ============================================
CREATE TABLE map_locations (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    position_x INTEGER,
    position_y INTEGER,
    location_type VARCHAR(50),
    parent_location_id INTEGER REFERENCES map_locations(id),
    location_image VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 人物关系表
-- ============================================
CREATE TABLE character_relationships (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    character_id_1 INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    character_id_2 INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    relationship_type VARCHAR(50),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 时间轴事件关联表
-- ============================================
CREATE TABLE timeline_event_characters (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    character_id INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    role_in_event VARCHAR(100)
);

CREATE TABLE timeline_event_scenes (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    scene_id INTEGER REFERENCES scenes(id) ON DELETE CASCADE
);

CREATE TABLE timeline_event_foreshadows (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    foreshadow_id INTEGER REFERENCES foreshadows(id) ON DELETE CASCADE,
    action_type VARCHAR(20)
);

CREATE TABLE timeline_event_outlines (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    outline_id INTEGER REFERENCES outlines(id) ON DELETE CASCADE
);

CREATE TABLE scene_locations (
    id SERIAL PRIMARY KEY,
    scene_id INTEGER REFERENCES scenes(id) ON DELETE CASCADE,
    map_location_id INTEGER REFERENCES map_locations(id) ON DELETE CASCADE
);

-- ============================================
-- 标签关联表
-- ============================================
CREATE TABLE character_tags (
    id SERIAL PRIMARY KEY,
    character_id INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(character_id, tag_id)
);

CREATE TABLE scene_tags (
    id SERIAL PRIMARY KEY,
    scene_id INTEGER REFERENCES scenes(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(scene_id, tag_id)
);

CREATE TABLE foreshadow_tags (
    id SERIAL PRIMARY KEY,
    foreshadow_id INTEGER REFERENCES foreshadows(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(foreshadow_id, tag_id)
);

CREATE TABLE outline_tags (
    id SERIAL PRIMARY KEY,
    outline_id INTEGER REFERENCES outlines(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(outline_id, tag_id)
);

CREATE TABLE timeline_event_tags (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(timeline_event_id, tag_id)
);

CREATE TABLE map_location_tags (
    id SERIAL PRIMARY KEY,
    map_location_id INTEGER REFERENCES map_locations(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(map_location_id, tag_id)
);

-- ============================================
-- 索引
-- ============================================
CREATE INDEX idx_characters_novel ON characters(novel_id);
CREATE INDEX idx_scenes_novel ON scenes(novel_id);
CREATE INDEX idx_foreshadows_novel ON foreshadows(novel_id);
CREATE INDEX idx_outlines_novel ON outlines(novel_id);
CREATE INDEX idx_timeline_events_novel ON timeline_events(novel_id);
CREATE INDEX idx_map_locations_novel ON map_locations(novel_id);
CREATE INDEX idx_tags_novel ON tags(novel_id);
CREATE INDEX idx_images_novel ON images(novel_id);
CREATE INDEX idx_character_relationships_novel ON character_relationships(novel_id);

CREATE INDEX idx_timeline_events_real_order ON timeline_events(real_order);
CREATE INDEX idx_outlines_plot_order ON outlines(plot_order);
CREATE INDEX idx_map_locations_parent ON map_locations(parent_location_id);
CREATE INDEX idx_timeline_event_characters_event ON timeline_event_characters(timeline_event_id);
CREATE INDEX idx_timeline_event_characters_character ON timeline_event_characters(character_id);
CREATE INDEX idx_timeline_event_scenes_event ON timeline_event_scenes(timeline_event_id);
CREATE INDEX idx_timeline_event_foreshadows_event ON timeline_event_foreshadows(timeline_event_id);
CREATE INDEX idx_timeline_event_outlines_event ON timeline_event_outlines(timeline_event_id);

CREATE INDEX idx_character_tags_character ON character_tags(character_id);
CREATE INDEX idx_character_tags_tag ON character_tags(tag_id);
CREATE INDEX idx_scene_tags_scene ON scene_tags(scene_id);
CREATE INDEX idx_scene_tags_tag ON scene_tags(tag_id);
CREATE INDEX idx_foreshadow_tags_foreshadow ON foreshadow_tags(foreshadow_id);
CREATE INDEX idx_foreshadow_tags_tag ON foreshadow_tags(tag_id);
CREATE INDEX idx_outline_tags_outline ON outline_tags(outline_id);
CREATE INDEX idx_outline_tags_tag ON outline_tags(tag_id);
CREATE INDEX idx_timeline_event_tags_event ON timeline_event_tags(timeline_event_id);
CREATE INDEX idx_timeline_event_tags_tag ON timeline_event_tags(tag_id);
CREATE INDEX idx_map_location_tags_location ON map_location_tags(map_location_id);
CREATE INDEX idx_map_location_tags_tag ON map_location_tags(tag_id);
