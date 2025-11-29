-- 小说写作辅助系统数据库设计 (PostgreSQL)

-- 人物表
CREATE TABLE characters (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    personality TEXT,
    appearance TEXT,
    background TEXT,
    role VARCHAR(50), -- 主角、配角、反派等
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 场景表
CREATE TABLE scenes (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    location VARCHAR(200),
    atmosphere TEXT, -- 氛围描述
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 伏笔表
CREATE TABLE foreshadows (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    laid_at VARCHAR(100), -- 埋下伏笔的位置
    revealed_at VARCHAR(100), -- 揭示伏笔的位置
    status VARCHAR(20) DEFAULT 'pending', -- pending, revealed, abandoned
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 大纲表
CREATE TABLE outlines (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    chapter_number INTEGER,
    plot_order INTEGER, -- 情节顺序
    status VARCHAR(20) DEFAULT 'planning', -- planning, writing, completed
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 时间轴事件表
CREATE TABLE timeline_events (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    event_time VARCHAR(100), -- 故事内的时间（可以是虚构时间）
    real_order INTEGER, -- 实际发生顺序
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 地图位置表
CREATE TABLE map_locations (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    position_x INTEGER, -- x坐标
    position_y INTEGER, -- y坐标
    location_type VARCHAR(50), -- 城市、建筑、地点等
    parent_location_id INTEGER REFERENCES map_locations(id), -- 父级位置（如某个城市下的建筑）
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 时间轴事件-人物关联表
CREATE TABLE timeline_event_characters (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    character_id INTEGER REFERENCES characters(id) ON DELETE CASCADE,
    role_in_event VARCHAR(100) -- 该人物在事件中的角色
);

-- 时间轴事件-场景关联表
CREATE TABLE timeline_event_scenes (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    scene_id INTEGER REFERENCES scenes(id) ON DELETE CASCADE
);

-- 时间轴事件-伏笔关联表
CREATE TABLE timeline_event_foreshadows (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    foreshadow_id INTEGER REFERENCES foreshadows(id) ON DELETE CASCADE,
    action_type VARCHAR(20) -- 'laid' 或 'revealed'
);

-- 时间轴事件-大纲关联表
CREATE TABLE timeline_event_outlines (
    id SERIAL PRIMARY KEY,
    timeline_event_id INTEGER REFERENCES timeline_events(id) ON DELETE CASCADE,
    outline_id INTEGER REFERENCES outlines(id) ON DELETE CASCADE
);

-- 场景-地图位置关联表
CREATE TABLE scene_locations (
    id SERIAL PRIMARY KEY,
    scene_id INTEGER REFERENCES scenes(id) ON DELETE CASCADE,
    map_location_id INTEGER REFERENCES map_locations(id) ON DELETE CASCADE
);

-- 创建索引以提高查询性能
CREATE INDEX idx_timeline_events_real_order ON timeline_events(real_order);
CREATE INDEX idx_outlines_plot_order ON outlines(plot_order);
CREATE INDEX idx_map_locations_parent ON map_locations(parent_location_id);
CREATE INDEX idx_timeline_event_characters_event ON timeline_event_characters(timeline_event_id);
CREATE INDEX idx_timeline_event_characters_character ON timeline_event_characters(character_id);
CREATE INDEX idx_timeline_event_scenes_event ON timeline_event_scenes(timeline_event_id);
CREATE INDEX idx_timeline_event_foreshadows_event ON timeline_event_foreshadows(timeline_event_id);
CREATE INDEX idx_timeline_event_outlines_event ON timeline_event_outlines(timeline_event_id);
