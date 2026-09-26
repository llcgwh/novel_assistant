# 维护与验收

运行环境：Java 17+、Maven、PostgreSQL，前端 Node.js 22.12+。开发服务器默认把 `/api` 代理到 `http://localhost:8080`，接口和图片使用同一来源。可用 `VITE_API_PROXY_TARGET` 改变开发代理目标；`VITE_API_BASE_URL` 用于配置单独的 API 地址。

## 表单保存

十类资料编辑使用 `/api/novels/{novelId}/forms/{resource}`：人物、场景、伏笔、大纲、时间轴、地图位置、世界观、标签、人物关系及关系组。主体和关联 ID 在同一事务内保存，失败一起回滚。创建请求提供 UUID 格式的 `Idempotency-Key`，弹窗关闭前重试沿用同一值；服务器保留 token 与记录 ID 的映射，响应丢失后重试更新同一条记录。

锁按小说串行化保存，适合当前单用户工作流。旧接口仍兼容，但直接调用旧创建接口不具备此幂等保证。历史 token 不自动过期；备份恢复或删除已移除其记录时，旧 token 返回不存在，重新打开编辑器才开始新提交。关闭 Hibernate 自动建表的安装需执行 `database/migration_v5.sql`。

## 外观迁移

设置页的“导出外观偏好”和“导入外观偏好”独立于小说数据备份，包含背景、透明度、当前小说地图背景选择。服务器上的本地背景会嵌入文件，外部图片保留链接。导入地图图片时，在目标小说创建新图片记录，避免沿用旧数据库 ID。

全局背景及导出时嵌入的背景限 2 MiB；导入文件限 16 MiB。地图可选择“自动”或“无背景”。浏览器禁用存储或空间不足时会显示失败。迁移会替换本浏览器当前设置，建议先导出旧偏好。

## 孤立图片隔离

**先停止使用同一数据库及 uploads 的所有后端实例**，备份数据库、上传目录和密钥，确保维护命令使用相同配置及工作目录。在 `backend/` 执行：

```sh
mvn spring-boot:run -Dspring-boot.run.arguments="--maintenance.operation=scan-images"
mvn spring-boot:run -Dspring-boot.run.arguments="--maintenance.operation=quarantine-images"
mvn spring-boot:run -Dspring-boot.run.arguments="--maintenance.operation=restore-images --maintenance.batch=UUID"
```

`scan-images` 只列出数据库图片表未登记、位于数字小说目录下、最后修改超过一小时的普通文件。它保留所有已登记图片（即便当前资料未引用），跳过符号链接和其他目录。命令本身不删除图片；正常应用启动迁移仍会执行。

`quarantine-images` 移入 `uploads/.quarantine/{UUID}/{novelId}/`，输出批次 ID。`restore-images` 恢复此批次，不覆盖同名文件；中途失败的批次也可恢复。隔离不会释放磁盘空间，永久删除需自行确认备份和保留期，不提供自动清空。自定义上传路径通过 `APP_UPLOAD_DIR` 指定。

## 密钥轮换

停止所有后端实例，备份数据库及对应旧密钥。在 `backend/` 运行，保持当前密钥配置不变，指定一个**尚不存在**的新文件：

```sh
mvn spring-boot:run -Dspring-boot.run.arguments="--maintenance.operation=rotate-key --maintenance.new-key-file=/absolute/private/next-webdav.key"
```

工具先解密全部现有凭据，生成新密钥，然后在一个数据库事务内重新加密。成功后退出，不继续提供 HTTP 服务。将 `NOVEL_CREDENTIAL_KEY_FILE` 改为输出的新文件路径，并清除优先级更高的旧 `NOVEL_CREDENTIAL_KEY` 环境变量，再重启服务。验证 WebDAV 连接后，仍需把旧密钥与旧数据库备份一起保管。

失败时不要删除任一密钥。事务未提交则仍使用旧密钥；提交后进程意外退出则需使用新密钥。以正常启动的凭据校验确定配套关系，不要覆盖密钥内容。如果要回退整个数据库，必须使用该备份对应的旧密钥。空凭据库也会生成新密钥文件。

## 自动化验证

```sh
cd backend
mvn test
cd ../frontend-vue
npm ci
npm test
npm run build
npm audit
```

真实服务回归脚本需要可连接的本地 PostgreSQL（测试账户拥有创建数据库权限）、Java 17+、Maven，以及 Python 环境中的 WsgiDAV/cheroot。建议使用单独虚拟环境：

```sh
python3 -m venv /tmp/novel-it-env
/tmp/novel-it-env/bin/pip install WsgiDAV==4.3.5 cheroot==11.1.2
DAV_PYTHON=/tmp/novel-it-env/bin/python python3 scripts/integration-check.py
```

从仓库根目录运行。默认 PostgreSQL 工具目录为 `/Library/PostgreSQL/18/bin`，数据库账户读取后端本地配置；可用 `PG_BIN`、`PGUSER`、`PGPASSWORD`、`MVN`、`JAVA_HOME` 覆盖。脚本创建唯一的 `novel_it_*` 数据库，仅在该库写测试数据，启动本地 8081 后端及 8099 WebDAV，端口被占用则退出。它验证旧库迁移、并发幂等、事务回滚、含图片备份、WebDAV 上传/列表/恢复、跨小说恢复、密钥轮换及重启。

默认结束后停止服务、删除测试数据库；日志、测试图片和测试密钥留在输出的临时目录。加 `--keep` 可保留用于浏览器验收：

```sh
DAV_PYTHON=/tmp/novel-it-env/bin/python python3 scripts/integration-check.py --keep
cd frontend-vue
VITE_API_PROXY_TARGET=http://127.0.0.1:8081 npm run dev -- --host 127.0.0.1 --port 3001 --strictPort
```

完成后停止前端，并从根目录运行 `python3 scripts/integration-check.py --cleanup /输出目录/run.json`。若测试进程被强制杀死，使用同一清理命令。支持 `IT_PORT`、`DAV_PORT` 调整测试端口。

写作回归在上述 `--keep` 产生的独立环境执行一次，再进行浏览器验收：

```sh
python3 scripts/writing-integration-check.py /输出目录/run.json
```

它会校验测试库标记，验证正文版本冲突、反向链接、搜索、真实 DOCX 包、WebDAV 分支检测、恢复前完整副本、恢复后的关联及别名映射、另一设备采用云端作品标识。输出 `writing-result.json` 和虚构示例文稿。不要对同一测试环境反复运行以避免累积演示数据；需要完整重跑时创建新的独立测试环境。

写作新增 `writing_books`、`writing_chapters`、`writing_revisions`、`writing_sessions` 四张表。默认 `spring.jpa.hibernate.ddl-auto=update` 启动时创建；使用自行管理数据库结构的安装需先同步这些实体的表结构。旧小说第一次访问写作页才初始化工作区，不会自动把大纲转换成正文。

已用 PostgreSQL 18 和 WsgiDAV 4.3.5 通过集成测试；这不代表已验证每一家云盘或 NAS 的特殊行为。前端 npm 审计在 2026-09-26 为 0 项告警，后端已升级 Spring Boot 3.5.16 并使用 Hibernate 6 对应的 Jackson 模块；未声称后端或未来依赖永久无漏洞。
