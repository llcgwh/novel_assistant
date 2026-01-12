# 小说写作辅助系统

一个功能完整的小说创作辅助工具，帮助作者管理人物、场景、伏笔、大纲、时间轴、地图和人物关系等创作要素。

## 功能特点

### 核心功能模块

1. **多小说管理** - 支持管理多部小说作品
   - 创建、编辑、删除小说
   - 切换不同小说进行管理
   - 每部小说独立管理所有创作要素

2. **时间轴系统** - 管理故事事件的时间线
   - 添加、编辑、删除事件
   - 设置故事时间和发生顺序
   - 关联人物、场景、伏笔、大纲
   - 支持标签分类
   - 搜索过滤功能

3. **人物管理系统**
   - 记录人物姓名、角色定位
   - 描述性格、外貌、背景故事
   - 支持上传人物肖像图片
   - 标签分类管理
   - 卡片式展示，便于浏览

4. **场景管理系统**
   - 记录场景名称和位置
   - 描述场景详情和氛围
   - 支持上传场景图片
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
   - 支持位置层级关系
   - 自动绘制位置连线
   - 拖拽移动位置

8. **人物关系图谱**
   - 可视化展示人物关系网络
   - 添加、编辑人物间关系
   - 自定义关系类型和描述
   - Canvas 绘制关系连线
   - 拖拽调整人物位置

9. **标签系统**
   - 创建自定义标签
   - 自定义标签颜色
   - 为所有元素添加标签
   - 按标签筛选内容

10. **全局搜索**
    - 跨模块搜索所有内容
    - 搜索人物、场景、伏笔、大纲、时间轴事件
    - 快速定位和跳转

11. **数据导出**
    - 导出小说所有数据为 JSON 格式
    - 便于备份和迁移

### 系统间关联

- **时间轴事件**可以关联：
  - 涉及的人物
  - 发生的场景
  - 相关的伏笔
  - 对应的大纲章节

- 点击任何关联标签即可跳转到对应系统查看详情

## 技术架构

### 后端
- **框架**: Spring Boot 3.2.0
- **数据库**: PostgreSQL
- **ORM**: Spring Data JPA
- **语言**: Java 17
- **文件上传**: 支持图片上传存储

### 前端（原生版本）
- **技术**: HTML5 + CSS3 + JavaScript (原生)
- **UI**: 响应式设计，卡片式布局
- **API 通信**: Fetch API
- **Canvas**: 地图和关系图绘制

### 前端（Vue 3 版本）
- **框架**: Vue 3 (Composition API)
- **构建工具**: Vite 6
- **状态管理**: Pinia
- **路由**: Vue Router 4
- **语言**: TypeScript
- **HTTP 客户端**: Axios
- **样式**: SCSS

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

### 启动前端（原生版本）

使用任何Web服务器托管前端文件，例如：

**使用 Python：**
```bash
cd frontend
python -m http.server 8000
```

**使用 Node.js http-server：**
```bash
cd frontend
npx http-server -p 8000
```

然后访问 `http://localhost:8000`

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
- 首次进入系统，点击"创建小说"
- 填写小说名称和描述
- 选择小说进入管理界面

### 2. 创建人物
- 点击"人物管理"标签
- 点击"添加人物"按钮
- 填写人物信息（姓名必填）
- 可选择添加标签
- 点击"创建"

### 3. 创建场景
- 点击"场景管理"标签
- 点击"添加场景"按钮
- 填写场景信息
- 可选择添加标签
- 点击"创建"

### 4. 创建伏笔
- 点击"伏笔管理"标签
- 点击"添加伏笔"按钮
- 填写伏笔标题、内容、埋下/揭示章节
- 选择状态和标签
- 点击"创建"

### 5. 创建大纲
- 点击"大纲管理"标签
- 点击"添加大纲"按钮
- 填写标题、内容、排序
- 选择状态和标签
- 点击"创建"

### 6. 使用时间轴
- 点击"时间轴"标签
- 点击"添加事件"创建事件
- 填写事件标题、故事时间、发生顺序、描述
- 可在创建时选择标签
- 创建后点击"关联"按钮管理关联
- 勾选相关的人物、场景、伏笔、大纲
- 点击"保存"

### 7. 使用地图系统
- 点击"地图系统"标签
- 可上传背景地图图片
- 直接点击画布添加位置
- 或点击"添加位置"按钮
- 填写位置信息和坐标
- 可选择上级位置建立层级关系

### 8. 使用人物关系图谱
- 点击"人物关系"标签
- 点击"添加关系"按钮
- 选择两个人物
- 填写关系类型和描述
- 拖拽人物节点调整布局

### 9. 管理标签
- 点击"标签管理"标签
- 创建新标签并选择颜色
- 在各模块中为元素添加标签

### 10. 全局搜索
- 使用顶部搜索框
- 输入关键词搜索所有内容
- 点击结果跳转到对应模块

## API 端点

### 小说 (Novels)
- GET `/api/novels` - 获取所有小说
- GET `/api/novels/{id}` - 获取单个小说
- POST `/api/novels` - 创建小说
- PUT `/api/novels/{id}` - 更新小说
- DELETE `/api/novels/{id}` - 删除小说

### 人物 (Characters)
- GET `/api/novels/{novelId}/characters` - 获取小说的所有人物
- GET `/api/characters/{id}` - 获取单个人物
- POST `/api/novels/{novelId}/characters` - 创建人物
- PUT `/api/characters/{id}` - 更新人物
- DELETE `/api/characters/{id}` - 删除人物
- POST `/api/characters/{id}/tags` - 设置人物标签

### 场景 (Scenes)
- GET `/api/novels/{novelId}/scenes` - 获取小说的所有场景
- GET `/api/scenes/{id}` - 获取单个场景
- POST `/api/novels/{novelId}/scenes` - 创建场景
- PUT `/api/scenes/{id}` - 更新场景
- DELETE `/api/scenes/{id}` - 删除场景
- POST `/api/scenes/{id}/tags` - 设置场景标签

### 伏笔 (Foreshadows)
- GET `/api/novels/{novelId}/foreshadows` - 获取小说的所有伏笔
- GET `/api/foreshadows/{id}` - 获取单个伏笔
- POST `/api/novels/{novelId}/foreshadows` - 创建伏笔
- PUT `/api/foreshadows/{id}` - 更新伏笔
- DELETE `/api/foreshadows/{id}` - 删除伏笔
- POST `/api/foreshadows/{id}/tags` - 设置伏笔标签

### 大纲 (Outlines)
- GET `/api/novels/{novelId}/outlines` - 获取小说的所有大纲
- GET `/api/outlines/{id}` - 获取单个大纲
- POST `/api/novels/{novelId}/outlines` - 创建大纲
- PUT `/api/outlines/{id}` - 更新大纲
- DELETE `/api/outlines/{id}` - 删除大纲
- POST `/api/outlines/{id}/tags` - 设置大纲标签

### 时间轴事件 (Timeline Events)
- GET `/api/novels/{novelId}/timeline-events` - 获取小说的所有事件
- GET `/api/timeline-events/{id}` - 获取单个事件
- POST `/api/novels/{novelId}/timeline-events` - 创建事件
- PUT `/api/timeline-events/{id}` - 更新事件
- DELETE `/api/timeline-events/{id}` - 删除事件
- POST `/api/timeline-events/{id}/relations` - 设置事件关联
- POST `/api/timeline-events/{id}/tags` - 设置事件标签

### 地图位置 (Map Locations)
- GET `/api/novels/{novelId}/map-locations` - 获取小说的所有位置
- GET `/api/map-locations/{id}` - 获取单个位置
- POST `/api/novels/{novelId}/map-locations` - 创建位置
- PUT `/api/map-locations/{id}` - 更新位置
- DELETE `/api/map-locations/{id}` - 删除位置
- POST `/api/novels/{novelId}/map-background` - 设置地图背景

### 人物关系 (Relationships)
- GET `/api/novels/{novelId}/relationships` - 获取小说的所有关系
- GET `/api/relationships/{id}` - 获取单个关系
- POST `/api/novels/{novelId}/relationships` - 创建关系
- PUT `/api/relationships/{id}` - 更新关系
- DELETE `/api/relationships/{id}` - 删除关系

### 标签 (Tags)
- GET `/api/novels/{novelId}/tags` - 获取小说的所有标签
- GET `/api/tags/{id}` - 获取单个标签
- POST `/api/novels/{novelId}/tags` - 创建标签
- PUT `/api/tags/{id}` - 更新标签
- DELETE `/api/tags/{id}` - 删除标签

### 图片 (Images)
- POST `/api/images/upload` - 上传图片
- GET `/api/images/{id}` - 获取图片
- DELETE `/api/images/{id}` - 删除图片

### 搜索 (Search)
- GET `/api/novels/{novelId}/search?keyword=xxx` - 全局搜索

### 导出 (Export)
- GET `/api/novels/{novelId}/export` - 导出小说数据

## 项目结构

```
project/
├── backend/                      # 后端代码
│   ├── src/
│   │   └── main/
│   │       ├── java/com/novelwriting/
│   │       │   ├── config/       # 配置类
│   │       │   ├── entity/       # 实体类
│   │       │   ├── repository/   # 数据访问层
│   │       │   ├── service/      # 业务逻辑层
│   │       │   └── controller/   # 控制器层
│   │       └── resources/
│   │           └── application.properties
│   └── pom.xml
├── frontend/                     # 原生前端代码
│   ├── index.html
│   ├── css/
│   │   └── style.css
│   └── js/
│       ├── api.js                # API封装
│       ├── main.js               # 主逻辑
│       ├── characters.js         # 人物管理
│       ├── scenes.js             # 场景管理
│       ├── foreshadows.js        # 伏笔管理
│       ├── outlines.js           # 大纲管理
│       ├── timeline.js           # 时间轴系统
│       ├── map.js                # 地图系统
│       ├── tags.js               # 标签管理
│       ├── search.js             # 搜索功能
│       └── export.js             # 导出功能
├── frontend-vue/                 # Vue 3 前端代码
│   ├── src/
│   │   ├── api/                  # API 请求层
│   │   ├── assets/styles/        # SCSS 样式
│   │   ├── components/           # Vue 组件
│   │   │   ├── common/           # 通用组件
│   │   │   └── tags/             # 标签组件
│   │   ├── router/               # 路由配置
│   │   ├── stores/               # Pinia 状态管理
│   │   ├── types/                # TypeScript 类型
│   │   ├── views/                # 页面视图
│   │   ├── App.vue
│   │   └── main.ts
│   ├── package.json
│   ├── vite.config.ts
│   └── tsconfig.json
└── database/
    └── schema.sql                # 数据库脚本
```

## 常见问题

**Q: 数据库连接失败？**
A: 检查PostgreSQL是否运行，确认数据库名称、用户名和密码配置正确。

**Q: 前端无法连接后端？**
A: 确认后端已启动在8080端口，检查浏览器控制台是否有CORS错误。

**Q: 时间轴事件关联不生效？**
A: 确保先创建了人物、场景等元素，然后在时间轴事件中点击"关联"进行关联。

**Q: 地图位置不显示？**
A: 确认已设置了坐标值，坐标应在画布范围内。

**Q: 图片上传失败？**
A: 检查后端 uploads 目录权限，确保有写入权限。

**Q: Vue 版本启动报错？**
A: 确保 Node.js 版本 >= 18，尝试删除 node_modules 后重新 npm install。

## 许可证

本项目仅供学习和个人使用。

## 联系方式

如有问题或建议，欢迎提出Issue。
