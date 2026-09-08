# 小说写作辅助系统

> ⚠️ **本项目采用纯AI开发，所有代码及文字皆由AI操作，为试验性项目。**

一个功能完整的小说创作辅助工具，帮助作者管理人物、场景、伏笔、大纲、时间轴、地图和人物关系等创作要素。

## 功能特点

### 核心功能模块

1. **多小说管理** - 支持管理多部小说作品
   - 创建、编辑、删除小说
   - 上传小说封面图片
   - 按状态筛选（规划中/写作中/已完成/已暂停）
   - 搜索小说标题
   - 液态玻璃风格卡片展示

2. **时间轴系统** - 管理故事事件的时间线
   - 添加、编辑、删除事件
   - 设置故事时间和发生顺序
   - 关联人物、场景、伏笔、大纲
   - **点击关联标签直接跳转到对应条目编辑页**
   - 支持标签分类与搜索过滤

3. **人物管理系统**
   - 记录人物姓名、角色定位
   - 描述性格、外貌、背景故事
   - **支持上传人物肖像图片**
   - 标签分类管理
   - 卡片式展示，便于浏览

4. **场景管理系统**
   - 记录场景名称和位置
   - 描述场景详情和氛围
   - **支持上传场景图片**
   - 标签分类管理

5. **伏笔管理系统**
   - 记录伏笔标题和内容
   - 标记埋下和揭示章节
   - 状态跟踪（未揭示/已揭示/已废弃）
   - 标签分类管理
   - 状态过滤功能

6. **大纲管理系统**
   - 管理章节大纲
   - 设置情节顺序
   - 跟踪写作进度（规划中/写作中/已完成）
   - 标签分类管理
   - 状态过滤功能

7. **地图系统**
   - 可视化地图画布
   - 支持上传背景地图图片
   - 点击画布添加位置
   - **关联已有场景自动填充信息**
   - 支持位置层级关系
   - 自动绘制位置连线

8. **🌌 世界观系统** ✨ 新增
   - 管理虚构世界的各类设定
   - 支持 8 大类别：地理、历史、文化、魔法/科技、种族、政治、宗教、其他
   - 每个条目可包含图片、详细描述和标签
   - 关联人物、场景、地图位置
   - 按类别筛选和关键词搜索
   - 导出世界观 Markdown

10. **人物关系图谱** ✨ 更新
    - Canvas 可视化展示人物关系网络
    - 添加、编辑人物间两两关系，自定义关系类型和描述
    - **新增关系组功能**：创建关系组，支持组嵌套（父子组）
    - 一次性添加多个角色到关系组
    - 侧边栏树形展示关系组及成员
    - **悬浮角色节点显示该角色的所有关系及所属关系组**
    - **点击人物节点跳转到人物编辑页**

11. **标签系统**
    - 创建自定义标签
    - 自定义标签颜色（圆形取色器）
    - 为所有元素添加标签
    - 按标签筛选内容

12. **全局搜索**
    - 跨模块搜索所有内容
    - 搜索人物、场景、伏笔、大纲、时间轴事件、地图位置
    - 快速定位和跳转

13. **全局设置**
    - 自定义全局背景图片
    - 覆盖层透明度调节
    - 设置持久化到本地存储

14. **数据导出**
    - 导出小说所有数据为 JSON / Markdown 格式
    - 导出人物/大纲/世界观 Markdown
    - 便于备份和分享

15. **☁️ WebDAV 多终端同步** ✨ 新增
    - 配置 WebDAV 服务器（NextCloud / ownCloud / 坚果云 / 群晖 NAS）
    - 一键上传备份到云端
    - 从云端恢复数据（完整导入，含新旧 ID 自动映射）
    - 管理云端备份文件（查看/删除）
    - 自动同步开关（计划中）
    - 密码加密存储，接口与 JSON 导出不返回密码；旧明文在升级启动时迁移

### 系统间关联

- **时间轴事件**可以关联：
  - 涉及的人物
  - 发生的场景
  - 相关的伏笔
  - 对应的大纲章节

- **点击关联标签**直接跳转到对应条目并自动打开编辑弹窗

### UI 设计

- **液态玻璃风格（Glassmorphism）**：毛玻璃背景、半透明边框、柔和阴影
- **自定义下拉选择器**：统一风格的选项面板，平滑动画
- **图标按钮**：编辑/标签/删除按钮带 emoji 前缀，视觉一致
- **响应式布局**：适配桌面和移动端
- **渐变主色调**：紫色系主按钮，状态彩色徽章

## 技术架构

### 后端
- **框架**: Spring Boot 3.2.0
- **数据库**: PostgreSQL
- **ORM**: Spring Data JPA
- **语言**: Java 17
- **文件上传**: 支持图片上传存储

### 前端（Vue 3 版本）✨ 当前主力
- **框架**: Vue 3 (Composition API)
- **构建工具**: Vite 6
- **状态管理**: Pinia
- **路由**: Vue Router 4
- **语言**: TypeScript
- **HTTP 客户端**: Axios
- **样式**: SCSS（液态玻璃主题）

> ⚠️ **前端已全面迁移至 Vue 3 版本，原版原生前端（`frontend/`）已废弃，缺失封面上传、全局设置、自定义下拉菜单、液态玻璃主题、关联跳转编辑等多项功能，不再维护。**

## 备份恢复边界

JSON 备份包含关系组、成员、标签和场景地图关联，恢复时会重建对应 ID。导入前检查数据结构、引用及父子层级，恢复失败会回滚数据库变更。

在小说设置页可下载和恢复 **含图片备份**（`.backup.json`）；WebDAV 新备份也采用这个格式。它包含已上传图片的内容及校验值，恢复到另一部小说或另一台设备时会重新生成图片地址，恢复封面、标题、简介等元数据，并保留目标端 WebDAV 配置。地图页可从服务器找回最新上传的地图背景。

恢复会替换当前小说的关联数据和图片记录。旧版普通 JSON 仍可恢复，但不带图片内容，也无法补回未导出的关系组。外部图片链接、浏览器背景偏好及地图背景清除偏好不打包。每张图片最多 8 MiB、图片合计最多 32 MiB、备份文件最多 48 MiB；缺失图片、损坏内容或超限会报错。

恢复失败会回滚数据库并清理此次新建的图片文件。成功恢复后，旧图片文件保留在 uploads 中以便人工找回，不自动清理。详见 [分批修复记录](docs/maintenance-batches.md)。

## WebDAV 密钥与升级

WebDAV 密码使用 AES-256-GCM 加密存入数据库。默认密钥位于后端工作目录的 `.secrets/webdav.key`，请单独安全备份；也可通过环境变量设置固定路径或密钥。启动时自动迁移旧明文，密钥错误或丢失会明确报错。升级步骤见 [密码存储说明](docs/credential-storage.md)。

## 安装和运行

### 前置要求

- Java 17 或更高版本
- PostgreSQL 数据库
- Maven 3.x
- Node.js 18+ (Vue 版本)

### 数据库设置

1. 创建PostgreSQL数据库：
```sql
CREATE DATABASE novel_writing;
```

2. 执行数据库脚本：
```bash
psql -U postgres -d novel_writing -f database/schema.sql
```

3. 修改配置文件（如需要）：
编辑 `backend/src/main/resources/application.properties`
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/novel_writing
spring.datasource.username=your_username
spring.datasource.password=your_password
```

### 启动后端

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

后端将在 `http://localhost:8080` 启动

### 启动前端（Vue 3 版本）

```bash
cd frontend-vue
npm install
npm run dev
```

开发服务器将在 `http://localhost:5173` 启动

**生产构建：**
```bash
npm run build
```

## 使用指南

### 1. 选择/创建小说
- 首次进入系统，点击"✨ 创建新小说"
- 填写小说名称、作者、类型、简介
- 可上传封面图片
- 选择小说进入管理界面

### 2. 创建人物
- 点击"人物管理"标签
- 点击"+ 添加人物"按钮
- 填写人物信息（姓名必填）
- 可上传人物肖像图片
- 可选择添加标签
- 点击"确定"

### 3. 创建场景
- 点击"场景管理"标签
- 点击"+ 添加场景"按钮
- 填写场景信息
- 可上传场景图片
- 可选择添加标签

### 4. 创建伏笔
- 点击"伏笔管理"标签
- 点击"+ 添加伏笔"按钮
- 填写伏笔标题、内容、埋下/揭示章节
- 选择状态和标签

### 5. 创建大纲
- 点击"大纲管理"标签
- 点击"+ 添加大纲"按钮
- 填写标题、内容、排序
- 选择状态和标签

### 6. 使用时间轴
- 点击"时间轴"标签
- 点击"+ 添加事件"创建事件
- 填写事件标题、故事时间、发生顺序、描述
- 创建后点击"🔗 关联"按钮管理关联
- 勾选相关的人物、场景、伏笔、大纲
- **点击关联标签可跳转到对应条目编辑**

### 7. 使用地图系统
- 点击"地图系统"标签
- 可上传背景地图图片
- 直接点击画布添加位置
- 或点击"+ 添加位置"按钮
- **可从已有场景下拉选择，自动填充信息**
- 可选择上级位置建立层级关系

### 8. 使用人物关系图谱
- 点击"人物关系"标签
- 点击"+ 添加关系"按钮添加两两人物关系
- 选择两个人物和关系类型
- **点击"+ 创建关系组"创建关系组**
- 填写组名、描述，选择父组（可选，用于嵌套）
- 勾选成员角色（支持多选），一次性添加多个角色
- 右侧面板树形展示所有关系组及成员
- **悬浮画布中的人物节点可查看该角色的所有关系及所属关系组**
- **点击画布中的人物节点可跳转编辑该人物**

### 9. 管理标签
- 点击"标签管理"标签
- 创建新标签并选择颜色（圆形取色器）
- 在各模块中为元素添加标签

### 10. 使用世界观
- 点击"世界观"标签
- 按类别筛选（地理/历史/文化/魔法科技/种族/政治/宗教/其他）
- 点击"+ 添加条目"创建世界观设定
- 填写名称、类别、详细描述
- 可上传条目图片、添加标签
- 关联人物、场景、地图位置
- 支持关键词搜索

### 12. 全局搜索
- 使用顶部搜索框
- 输入关键词搜索所有内容
- 点击结果跳转到对应模块

### 13. 全局设置
- 点击 ⚙️ 设置按钮
- 上传全局背景图片
- 调节覆盖层透明度

### 14. WebDAV 云同步 ✨
- 在小说设置页配置 WebDAV 服务器
- 填写服务器地址、用户名、密码
- 点击"测试连接"验证配置
- 点击"立即同步（上传）"备份数据到云端
- 点击"从云端恢复"选择备份文件恢复数据
- 可删除不需要的云端备份文件
- 密码存入数据库，后续使用无需重新输入

## API 端点

### 小说 (Novels)
- GET `/api/novels` - 获取所有小说
- GET `/api/novels/{id}` - 获取单个小说
- POST `/api/novels` - 创建小说
- PUT `/api/novels/{id}` - 更新小说
- DELETE `/api/novels/{id}` - 删除小说
- GET `/api/novels/search?title=xxx` - 搜索小说
- GET `/api/novels/status/{status}` - 按状态筛选

### 人物 (Characters)
- GET `/api/novels/{novelId}/characters` - 获取小说的所有人物
- POST `/api/novels/{novelId}/characters` - 创建人物
- PUT `/api/novels/{novelId}/characters/{id}` - 更新人物
- DELETE `/api/novels/{novelId}/characters/{id}` - 删除人物
- PUT `/api/novels/{novelId}/characters/{id}/tags` - 设置人物标签
- GET `/api/novels/{novelId}/characters/search?keyword=xxx` - 搜索人物

### 场景 (Scenes)
- GET `/api/novels/{novelId}/scenes` - 获取小说的所有场景
- POST `/api/novels/{novelId}/scenes` - 创建场景
- PUT `/api/novels/{novelId}/scenes/{id}` - 更新场景
- DELETE `/api/novels/{novelId}/scenes/{id}` - 删除场景
- PUT `/api/novels/{novelId}/scenes/{id}/tags` - 设置场景标签

### 伏笔 (Foreshadows)
- GET `/api/novels/{novelId}/foreshadows` - 获取小说的所有伏笔
- POST `/api/novels/{novelId}/foreshadows` - 创建伏笔
- PUT `/api/novels/{novelId}/foreshadows/{id}` - 更新伏笔
- DELETE `/api/novels/{novelId}/foreshadows/{id}` - 删除伏笔
- PUT `/api/novels/{novelId}/foreshadows/{id}/tags` - 设置伏笔标签

### 大纲 (Outlines)
- GET `/api/novels/{novelId}/outlines` - 获取小说的所有大纲
- POST `/api/novels/{novelId}/outlines` - 创建大纲
- PUT `/api/novels/{novelId}/outlines/{id}` - 更新大纲
- DELETE `/api/novels/{novelId}/outlines/{id}` - 删除大纲
- PUT `/api/novels/{novelId}/outlines/{id}/tags` - 设置大纲标签

### 时间轴事件 (Timeline Events)
- GET `/api/novels/{novelId}/timeline-events` - 获取小说的所有事件
- POST `/api/novels/{novelId}/timeline-events` - 创建事件
- PUT `/api/novels/{novelId}/timeline-events/{id}` - 更新事件
- DELETE `/api/novels/{novelId}/timeline-events/{id}` - 删除事件
- PUT `/api/novels/{novelId}/timeline-events/{id}/relations` - 设置事件关联
- PUT `/api/novels/{novelId}/timeline-events/{id}/tags` - 设置事件标签

### 地图位置 (Map Locations)
- GET `/api/novels/{novelId}/map-locations` - 获取小说的所有位置
- POST `/api/novels/{novelId}/map-locations` - 创建位置
- PUT `/api/novels/{novelId}/map-locations/{id}` - 更新位置
- DELETE `/api/novels/{novelId}/map-locations/{id}` - 删除位置
- POST `/api/novels/{novelId}/map-background` - 设置地图背景
- GET `/api/novels/{novelId}/map-locations/type/{type}` - 按类型筛选

### 人物关系 (Relationships)
- GET `/api/novels/{novelId}/relationships` - 获取小说的所有关系
- POST `/api/novels/{novelId}/relationships` - 创建关系
- PUT `/api/novels/{novelId}/relationships/{id}` - 更新关系
- DELETE `/api/novels/{novelId}/relationships/{id}` - 删除关系

### 关系组 (Relationship Groups) ✨
- GET `/api/novels/{novelId}/relationship-groups` - 获取小说的所有关系组
- GET `/api/novels/{novelId}/relationship-groups/{id}` - 获取单个关系组
- POST `/api/novels/{novelId}/relationship-groups` - 创建关系组
- PUT `/api/novels/{novelId}/relationship-groups/{id}` - 更新关系组
- DELETE `/api/novels/{novelId}/relationship-groups/{id}` - 删除关系组
- PUT `/api/novels/{novelId}/relationship-groups/{id}/characters` - 设置组成员
- POST `/api/novels/{novelId}/relationship-groups/{id}/characters/{characterId}` - 添加角色到组
- DELETE `/api/novels/{novelId}/relationship-groups/{id}/characters/{characterId}` - 从组中移除角色

### 标签 (Tags)
- GET `/api/novels/{novelId}/tags` - 获取小说的所有标签
- POST `/api/novels/{novelId}/tags` - 创建标签
- PUT `/api/novels/{novelId}/tags/{id}` - 更新标签
- DELETE `/api/novels/{novelId}/tags/{id}` - 删除标签

### 图片 (Images)
- GET `/api/novels/{novelId}/images` - 获取小说的所有图片
- POST `/api/novels/{novelId}/images` - 上传图片
- GET `/api/novels/{novelId}/images/{id}/file` - 获取图片文件
- DELETE `/api/novels/{novelId}/images/{id}` - 删除图片

### 搜索 (Search)
- GET `/api/novels/{novelId}/search?keyword=xxx` - 全局搜索

### 世界观 (Worldview) ✨
- GET `/api/novels/{novelId}/worldview` - 获取所有世界观条目
- POST `/api/novels/{novelId}/worldview` - 创建条目
- PUT `/api/novels/{novelId}/worldview/{id}` - 更新条目
- DELETE `/api/novels/{novelId}/worldview/{id}` - 删除条目
- PUT `/api/novels/{novelId}/worldview/{id}/tags` - 设置标签
- POST `/api/novels/{novelId}/worldview/{id}/characters/{characterId}` - 关联人物
- DELETE `/api/novels/{novelId}/worldview/{id}/characters/{characterId}` - 取消关联人物
- POST `/api/novels/{novelId}/worldview/{id}/scenes/{sceneId}` - 关联场景
- DELETE `/api/novels/{novelId}/worldview/{id}/scenes/{sceneId}` - 取消关联场景
- POST `/api/novels/{novelId}/worldview/{id}/locations/{locationId}` - 关联地图位置
- DELETE `/api/novels/{novelId}/worldview/{id}/locations/{locationId}` - 取消关联地图位置

### 导出 (Export)
- GET `/api/novels/{novelId}/export/json` - 导出 JSON
- GET `/api/novels/{novelId}/export/markdown` - 导出 Markdown
- GET `/api/novels/{novelId}/export/characters/markdown` - 导出人物
- GET `/api/novels/{novelId}/export/outlines/markdown` - 导出大纲
- GET `/api/novels/{novelId}/export/worldview/markdown` - 导出世界观 ✨

### WebDAV 同步 ✨
- POST `/api/novels/{novelId}/webdav/test` - 测试连接
- POST `/api/novels/{novelId}/webdav/sync/upload` - 上传备份
- POST `/api/novels/{novelId}/webdav/sync/download` - 下载并恢复
- GET `/api/novels/{novelId}/webdav/status` - 获取同步状态
- POST `/api/novels/{novelId}/webdav/config` - 保存配置
- GET `/api/novels/{novelId}/webdav/files` - 列出云端文件
- DELETE `/api/novels/{novelId}/webdav/files` - 删除云端文件

## 项目结构

```
project/
├── backend/                      # 后端代码
│   ├── src/
│   │   └── main/
│   │       ├── java/com/novelwriting/
│   │       │   ├── config/       # 配置类
│   │       │   ├── entity/       # 实体类（含 RelationshipGroup ✨）
│   │       │   ├── repository/   # 数据访问层
│   │       │   ├── service/      # 业务逻辑层（含 ExportService / ImportService / WebDavSyncService）
│   │       │   └── controller/   # 控制器层（含 WebDavController）
│   │       └── resources/
│   │           └── application.properties
│   └── pom.xml
├── frontend/                     # 原生前端代码
│   ├── index.html
│   ├── css/style.css
│   └── js/
│       ├── api.js / main.js
│       ├── characters.js / scenes.js / foreshadows.js
│       ├── outlines.js / timeline.js / map.js
│       ├── tags.js / search.js / export.js
├── frontend-vue/                 # Vue 3 前端代码
│   ├── src/
│   │   ├── api/                  # API 请求层
│   │   │   ├── characters.ts / scenes.ts / novels.ts
│   │   │   ├── foreshadows.ts / outlines.ts / timeline.ts
│   │   │   ├── mapLocations.ts / relationships.ts / relationshipGroups.ts / tags.ts
│   │   │   ├── images.ts / search.ts / export.ts
│   │   │   ├── worldview.ts / webdav.ts
│   │   │   ├── index.ts / request.ts
│   │   ├── assets/styles/        # SCSS 样式（液态玻璃主题）
│   │   │   ├── variables.scss    # 变量和主题色
│   │   │   ├── base.scss         # 基础样式和全局组件
│   │   │   └── components.scss   # 组件样式
│   │   ├── components/
│   │   │   ├── common/           # 通用组件
│   │   │   │   ├── BaseModal.vue     # 模态框
│   │   │   │   ├── BaseSelect.vue    # 自定义下拉选择器
│   │   │   │   ├── BasicCard.vue
│   │   │   │   ├── EmptyState.vue
│   │   │   │   ├── ImageUpload.vue   # 图片上传
│   │   │   │   ├── LoadingState.vue
│   │   │   │   ├── StatusBadge.vue
│   │   │   │   └── Toast.vue
│   │   │   └── tags/             # 标签组件
│   │   │       ├── TagList.vue
│   │   │       └── TagSelector.vue
│   │   ├── composables/          # 组合式函数
│   │   ├── router/index.ts       # 路由配置
│   │   ├── stores/               # Pinia 状态管理
│   │   │   ├── app.ts            # 全局设置（背景图等）
│   │   │   ├── novel.ts / characters.ts / scenes.ts
│   │   │   ├── foreshadows.ts / outlines.ts / timeline.ts
│   │   │   ├── map.ts / relationships.ts / relationshipGroups.ts / tags.ts
│   │   │   └── worldview.ts
│   │   ├── types/                # TypeScript 类型定义
│   │   │   └── worldview.ts / webdav.ts
│   │   ├── views/                # 页面视图
│   │   │   ├── NovelSelector.vue     # 首页 / 小说选择
│   │   │   ├── MainLayout.vue        # 主布局（导航+导出）
│   │   │   ├── TimelineView.vue      # 时间轴
│   │   │   ├── WorldviewView.vue     # 世界观 ✨
│   │   │   ├── CharactersView.vue    # 人物管理
│   │   │   ├── ScenesView.vue        # 场景管理
│   │   │   ├── ForeshadowsView.vue   # 伏笔管理
│   │   │   ├── OutlinesView.vue      # 大纲管理
│   │   │   ├── MapView.vue           # 地图系统
│   │   │   ├── RelationshipsView.vue # 人物关系（含关系组面板）✨
│   │   │   ├── TagsView.vue          # 标签管理
│   │   │   ├── SearchResultsView.vue # 搜索结果
│   │   │   └── SettingsView.vue      # 全局设置（含 WebDAV 同步）
│   │   ├── App.vue
│   │   └── main.ts
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts
│   └── tsconfig.json
└── database/
    ├── schema.sql                # 数据库基础架构
    └── migration_v3.sql          # v3 迁移（世界观 + WebDAV）
```

## 常见问题

**Q: 数据库连接失败？**
A: 检查PostgreSQL是否运行，确认数据库名称、用户名和密码配置正确。

**Q: 前端无法连接后端？**
A: 确认后端已启动在8080端口，检查浏览器控制台是否有CORS错误。

**Q: 时间轴事件关联不生效？**
A: 确保先创建了人物、场景等元素，然后在时间轴事件中点击"🔗 关联"进行关联。

**Q: 地图位置不显示？**
A: 确认已设置了坐标值，坐标应在画布范围内。

**Q: 图片上传失败？**
A: 检查后端 uploads 目录权限，确保有写入权限。

**Q: Vue 版本启动报错？**
A: 确保 Node.js 版本 >= 18，尝试删除 node_modules 后重新 npm install。

**Q: 页面空白/无法加载？**
A: 清除浏览器缓存和 localStorage，重新启动前端开发服务器。

## 许可证

本项目仅供学习和个人使用。

## 联系方式

如有问题或建议，欢迎提出Issue。
