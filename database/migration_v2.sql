-- 小说写作辅助系统数据库迁移脚本 v2.0
-- 新增功能：小说管理、标签系统、图片上传支持

-- ============================================
-- 1. 小说管理表（顶层管理）
-- ============================================
CREATE TABLE novels (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    author VARCHAR(100),
    genre VARCHAR(100),
    status VARCHAR(20) DEFAULT 'writing', -- planning, writing, completed, paused
    cover_image VARCHAR(500), -- 封面图片路径
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 2. 标签表
-- ============================================
CREATE TABLE tags (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    color VARCHAR(20) DEFAULT '#3498db', -- 标签颜色
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 标签唯一约束（同一小说内标签名唯一）
CREATE UNIQUE INDEX idx_tags_novel_name ON tags(novel_id, name);

-- ============================================
-- 3. 图片表（统一管理所有图片）
-- ============================================
CREATE TABLE images (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    original_name VARCHAR(255),
    file_path VARCHAR(500) NOT NULL,
    file_size INTEGER,
    mime_type VARCHAR(100),
    image_type VARCHAR(50), -- portrait(肖像), scene(场景图), map(地图), other
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 4. 为现有表添加 novel_id 外键
-- ============================================

-- 人物表添加 novel_id 和 portrait_image
ALTER TABLE characters
ADD COLUMN novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
ADD COLUMN portrait_image VARCHAR(500);

-- 场景表添加 novel_id 和 scene_image
ALTER TABLE scenes
ADD COLUMN novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
ADD COLUMN scene_image VARCHAR(500);

-- 伏笔表添加 novel_id
ALTER TABLE foreshadows
ADD COLUMN novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE;

-- 大纲表添加 novel_id
ALTER TABLE outlines
ADD COLUMN novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE;

-- 时间轴事件表添加 novel_id
ALTER TABLE timeline_events
ADD COLUMN novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE;

-- 地图位置表添加 novel_id 和 location_image
ALTER TABLE map_locations
ADD COLUMN novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
ADD COLUMN location_image VARCHAR(500);

-- ============================================
-- 5. 元素-标签关联表（多对多）
-- ============================================

-- 人物-标签关联
CREATE TABLE character_tags (
    id SERIAL PRIMARY KEY,
    character_id INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(character_id, tag_id)
);

-- 场景-标签关联
CREATE TABLE scene_tags (
    id SERIAL PRIMARY KEY,
    scene_id INTEGER REFERENCES scenes(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(scene_id, tag_id)
);

-- 伏笔-标签关联
CREATE TABLE foreshadow_tags (
    id SERIAL PRIMARY KEY,
    foreshadow_id INTEGER REFERENCES foreshadows(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(foreshadow_id, tag_id)
);

-- 大纲-标签关联
CREATE TABLE outline_tags (
    id SERIAL PRIMARY KEY,
    outline_id INTEGER REFERENCES outlines(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(outline_id, tag_id)
);

-- 时间轴事件-标签关联
CREATE TABLE timeline_event_tags (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(timeline_event_id, tag_id)
);

-- 地图位置-标签关联
CREATE TABLE map_location_tags (
    id SERIAL PRIMARY KEY,
    map_location_id INTEGER REFERENCES map_locations(id) ON DELETE CASCADE,
    tag_id INTEGER REFERENCES tags(id) ON DELETE CASCADE,
    UNIQUE(map_location_id, tag_id)
);

-- ============================================
-- 6. 人物关系表（用于可视化展示）
-- ============================================
CREATE TABLE character_relationships (
    id SERIAL PRIMARY KEY,
    novel_id INTEGER REFERENCES novels(id) ON DELETE CASCADE,
    character_id_1 INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    character_id_2 INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    relationship_type VARCHAR(50), -- 朋友、敌人、恋人、亲属等
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 7. 创建索引
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

-- 标签关联索引
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
