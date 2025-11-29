# 小说写作辅助系统

一个功能完整的小说创作辅助工具，帮助作者管理人物、场景、伏笔、大纲、时间轴和地图等创作要素。

## 功能特点

### 核心功能模块

1. **时间轴系统** - 管理故事事件的时间线
   - 添加、编辑、删除事件
   - 设置故事时间和发生顺序
   - 关联人物、场景、伏笔、大纲
   - 点击关联标签快速跳转

2. **人物管理系统**
   - 记录人物姓名、角色定位
   - 描述性格、外貌、背景故事
   - 卡片式展示，便于浏览

3. **场景管理系统**
   - 记录场景名称和位置
   - 描述场景详情和氛围
   - 支持场景分类管理

4. **伏笔管理系统**
   - 记录伏笔标题和内容
   - 标记埋下和揭示位置
   - 状态跟踪（未揭示/已揭示/已废弃）

5. **大纲管理系统**
   - 管理章节大纲
   - 设置情节顺序
   - 跟踪写作进度（规划中/写作中/已完成）

6. **地图系统**
   - 可视化地图画布
   - 点击添加位置
   - 支持位置层级关系
   - 自动绘制位置连线

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

### 前端
- **技术**: HTML5 + CSS3 + JavaScript (原生)
- **UI**: 响应式设计，卡片式布局
- **API 通信**: Fetch API

## 安装和运行

### 前置要求

- Java 17 或更高版本
- PostgreSQL 数据库
- Maven 3.x

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

### 启动前端

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

## 使用指南

### 1. 创建人物
- 点击"人物管理"标签
- 点击"添加人物"按钮
- 填写人物信息（姓名必填）
- 点击"创建"

### 2. 创建场景
- 点击"场景管理"标签
- 点击"添加场景"
钮
- 填写场景信息
- 点击"创建"

### 3. 创建伏笔
- 点击"伏笔管理"标签
- 点击"添加伏笔"按钮
- 填写伏笔标题、内容、埋下/揭示位置
- 选择状态
- 点击"创建"

### 4. 创建大纲
- 点击"大纲管理"标签
- 点击"添加大纲"按钮
- 填写标题、章节号、情节顺序、内容
- 点击"创建"

### 5. 使用时间轴
- 点击"时间轴"标签
- 点击"添加事件"创建事件
- 填写事件标题、故事时间、发生顺序、描述
- 创建后点击"管理关联"按钮
- 勾选相关的人物、场景、伏笔、大纲
- 点击"保存关联"

### 6. 使用地图系统
- 点击"地图系统"标签
- 直接点击画布添加位置
- 或点击"添加位置"按钮
- 填写位置信息和坐标
- 可选择上级位置建立层级关系

### 7. 系统间跳转
- 在时间轴中点击任何关联标签（人物、场景、伏笔、大纲）
- 自动跳转到对应系统并高亮显示该项

## API 端点

### 人物 (Characters)
- GET `/api/characters` - 获取所有人物
- GET `/api/characters/{id}` - 获取单个人物
- POST `/api/characters` - 创建人物
- PUT `/api/characters/{id}` - 更新人物
- DELETE `/api/characters/{id}` - 删除人物

### 场景 (Scenes)
- GET `/api/scenes` - 获取所有场景
- GET `/api/scenes/{id}` - 获取单个场景
- POST `/api/scenes` - 创建场景
- PUT `/api/scenes/{id}` - 更新场景
- DELETE `/api/scenes/{id}` - 删除场景

### 伏笔 (Foreshadows)
- GET `/api/foreshadows` - 获取所有伏笔
- GET `/api/foreshadows/{id}` - 获取单个伏笔
- POST `/api/foreshadows` - 创建伏笔
- PUT `/api/foreshadows/{id}` - 更新伏笔
- DELETE `/api/foreshadows/{id}` - 删除伏笔

### 大纲 (Outlines)
- GET `/api/outlines` - 获取所有大纲
- GET `/api/outlines/{id}` - 获取单个大纲
- POST `/api/outlines` - 创建大纲
- PUT `/api/outlines/{id}` - 更新大纲
- DELETE `/api/outlines/{id}` - 删除大纲

### 时间轴事件 (Timeline Events)
- GET `/api/timeline-events` - 获取所有事件（按顺序）
- GET `/api/timeline-events/{id}` - 获取单个事件
- POST `/api/timeline-events` - 创建事件
- PUT `/api/timeline-events/{id}` - 更新事件
- DELETE `/api/timeline-events/{id}` - 删除事件
- POST `/api/timeline-events/{id}/characters` - 关联人物
- POST `/api/timeline-events/{id}/scenes` - 关联场景
- POST `/api/timeline-events/{id}/foreshadows` - 关联伏笔
- POST `/api/timeline-events/{id}/outlines` - 关联大纲

### 地图位置 (Map Locations)
- GET `/api/map-locations` - 获取所有位置
- GET `/api/map-locations/{id}` - 获取单个位置
- POST `/api/map-locations` - 创建位置
- PUT `/api/map-locations/{id}` - 更新位置
- DELETE `/api/map-locations/{id}` - 删除位置

## 项目结构

```
project/
├── backend/                    # 后端代码
│   ├── src/
│   │   └── main/
│   │       ├── java/com/novelwriting/
│   │       │   ├── entity/     # 实体类
│   │       │   ├── repository/ # 数据访问层
│   │       │   ├── service/    # 业务逻辑层
│   │       │   └── controller/ # 控制器层
│   │       └── resources/
│   │           └── application.properties
│   └── pom.xml
├── frontend/                   # 前端代码
│   ├── index.html
│   ├── css/
│   │   └── style.css
│   └── js/
│       ├── api.js              # API封装
│       ├── main.js             # 主逻辑
│       ├── characters.js       # 人物管理
│       ├── scenes.js           # 场景管理
│       ├── foreshadows.js      # 伏笔管理
│       ├── outlines.js         # 大纲管理
│       ├── timeline.js         # 时间轴系统
│       └── map.js              # 地图系统
└── database/
    └── schema.sql              # 数据库脚本
```

## 后续改进建议 

1. **用户认证系统** - 添加登录功能，支持多用户
2. **数据导出** - 支持导出为PDF、Word等格式
3. **关系图谱** - 可视化展示人物、事件关系
4. **版本控制** - 支持大纲和内容的版本历史
5. **搜索功能** - 全局搜索所有内容
6. **标签系统** - 为各类元素添加标签便于分类
7. **备份恢复** - 自动备份数据
8. **富文本编辑器** - 更好的内容编辑体验
9. **图片上传** - 支持人物肖像、场景图片
10. **移动端适配** - 响应式设计优化

## 常见问题

**Q: 数据库连接失败？**
A: 检查PostgreSQL是否运行，确认数据库名称、用户名和密码配置正确。

**Q: 前端无法连接后端？**
A: 确认后端已启动在8080端口，检查浏览器控制台是否有CORS错误。

**Q: 时间轴事件关联不生效？**
A: 确保先创建了人物、场景等元素，然后在时间轴事件中点击"管理关联"进行关联。

**Q: 地图位置不显示？**
A: 确认已设置了坐标值，坐标应在画布范围内（0-800, 0-600）。

## 许可证

本项目仅供学习和个人使用。

## 联系方式

如有问题或建议，欢迎提出Issue。
