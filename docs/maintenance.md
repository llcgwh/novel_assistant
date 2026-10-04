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

创作便签和修订轮次的真实接口回归使用同一独立环境：

```sh
python3 scripts/writing-desk-check.py /输出目录/run.json
```

它创建自己的虚构作品，验证下一笔、任务、书签、轮次的版本保护、跨作品拒绝、备份往返和实际 WebDAV 文件，不修改浏览器验收作品。`writing_books.desk_data` 为可空 TEXT；旧记录读取为默认空文档。缺少 desk、rounds 或 ideas 的旧包禁止静默清除对应已有数据；遇到该提示应恢复到新作品。轮次和灵感属于 desk 文档，没有新增独立数据表。灵感最多 1,000 条，单条正文最多 20,000 字符、关联最多 200 章，与其他创作便签共用 2 MiB 上限。

灵感与创作统计有单独的真实接口回归，继续使用 `integration-check.py --keep` 创建的独立环境：

```sh
python3 scripts/idea-inbox-check.py /输出目录/run.json
python3 scripts/writing-stats-check.py /输出目录/run.json
# 加测 20,000 条专注回执、36,600 个日期的完整保留与容量拒绝
python3 scripts/writing-stats-check.py /输出目录/run.json --capacity
```

这些脚本创建各自的虚构作品，不启动或停止服务；统计回归检查历史目标、正文修改和定稿口径、专注终态幂等、备份／WebDAV 往返及旧包保护。使用非默认 PostgreSQL 端口时传入相同的 `PGPORT`；服务地址可用 `IT_PORT` 指定。输出分别为 `idea-inbox-result.json`、`writing-stats-result.json`，最终验收记录见 [实施记录](writing-implementation.md)。

## C12 统计升级与兼容

新增 `writing_books.stats_data` 可空 TEXT，旧书读取为空统计，不从现有正文、会话或今日目标反推历史。启用 `spring.jpa.hibernate.ddl-auto=update` 时由后端启动补列；手动管理结构时，先停止使用同一数据库的后端、备份数据库及对应上传目录和密钥，再对已有写作表执行 [database/migration_v6.sql](../database/migration_v6.sql)，随后部署新后端和前端。此迁移只增加统计列，不修改既有正文或会话。

原手输、粘贴、净增和活跃时间仍保存在 `writing_sessions`，按会话标识和递增序号更新。修订保存、定稿转换、每日目标及专注结果由服务端独立聚合，旧客户端替换 `preferences` 不会清空它们。成功正文保存对比前后纯文本；仅元数据或文字样式变化、失败和重试不会增加修订次数。新建、拆合章及整本恢复不产生这类活动，采用历史正文后正常保存则按实际文本变化处理。

新客户端向正文保存、偏好更新和 `/writing/stats/day` 发送顶层 `date` 与 `timezoneOffsetMinutes`，后者使用 JavaScript `getTimezoneOffset()` 的分钟及符号约定。请求初次构造时固定日期，重试不重取新日期。服务端据偏移判断当前本地日，只为当前日捕获或更新目标；旧日缺少快照保持未知。旧客户端未传日期／偏移时兼容使用服务端本地日，因此升级前的历史记录可能采用不同日期口径。

专注只在完成或取消后提交终态；每部作品内使用固定 UUID 去重，相同回执重试不改变统计，不同结果使用相同 UUID 返回冲突。前端保留冲突原件并提供 JSON 导出，同时继续上传其他正常时段；不会自动用另一终态覆盖服务端结果。回执随完整备份和 WebDAV 保存，恢复后仍可去重；备份校验同时核对回执与每日专注合计。缺少 `writing.stats` 的旧包若要覆盖已有统计，会在恢复前拒绝；可导入新作品。公开统计接口不返回全部回执，也不接受客户端整体覆盖统计文档。

统计文档上限 8 MiB、每日记录上限 36,600 行、专注终态上限 20,000 条；不自动清理。专注单次合计最多 24 小时、最多 366 个日期分段。超限或数据无效明确拒绝写入，不截断现有统计；容量满时已接收回执的相同重试仍可确认成功。前端保留失败的待上传记录并提示重试，容量不足需先解决容量原因，当前没有统计清理入口。未提交的专注、灵感和正文草稿不属于服务端备份，迁移前应先确认本机待保存状态。

已用 PostgreSQL 18 和 WsgiDAV 4.3.5 通过集成测试；这不代表已验证每一家云盘或 NAS 的特殊行为。前端 npm 审计在 2026-09-26 为 0 项告警，后端已升级 Spring Boot 3.5.16 并使用 Hibernate 6 对应的 Jackson 模块；未声称后端或未来依赖永久无漏洞。


## C10 操作记录与远端空间维护

手工管理数据库的安装执行 `database/migration_v7.sql`，新增 `backup_operations`、`backup_retention`、`backup_cleanups`；不改写已有小说资料。操作记录、策略及清理回执属于本服务数据库，不随小说包导入或恢复而回退，也不冒充其他独立服务端的日志。记录没有自动过期清理，备份数据库时一并保留。

操作日志使用独立事务记录成功、失败及请求结果。详情只保存阶段、错误类型、HTTP 状态、文件身份、容量和操作编号，不保存正文、密码或远端异常原文。进程意外中止的操作可能显示“处理中／待核对”；先核对记录与远端现状，不能把缺少完成回执当作没有发生删除。

保留策略默认最新 20 份及最近 30 天，只用于显式预览。预览绑定作品身份、同步基线、连接配置、策略版本及目录指纹，十分钟有效。确认后保存请求身份与结果；重复确认只读取同一执行结果。发生部分失败时已删除文件、未确认文件分别记录，不接着重跑旧删除请求。

清理仅作用于当前作品的 `novel-backups/ink-{bookUid}/`。所有云端头版本、根快照、本机恢复基线、未识别项和共享旧备份受到保护。先验证完整恢复包、保留版本连接信息，再执行删除，避免删去中间快照后产生伪分支。WebDAV 必须提供可验证的目录排他锁、有限租期和强 ETag；写入与删除使用条件请求，不能满足条件时停止。第三方云盘兼容性以实际服务测试为准，不因通过本地 WsgiDAV 就承诺全部 NAS 支持清理。

真实回归只接受 `integration-check.py --keep` 创建的独立数据库和本机 WebDAV：

```sh
python3 scripts/sync-maintenance-check.py /输出目录/run.json
```

脚本创建专用作品和历史快照，实际验证预览、取消、过期、目录或策略变动、重复确认、清理后的分支关系、旧设备冲突和恢复前副本。不得把生产数据库或真实远端路径传入此脚本。

## 本机开发文件与提交范围

IDEA 的本机设置由 `.idea/` 忽略，前端增量构建缓存由 `frontend-vue/*.tsbuildinfo` 忽略。取消这些文件的 Git 跟踪不删除本机副本；IDE 可继续使用本机 SDK 与注解处理设置，缓存由后续构建自动生成。共享 Java 版本与依赖以 `backend/pom.xml` 为准，IDE 通过 Maven 导入，并选择本机安装的兼容 JDK。不要将特定电脑的 SDK 注册名称当作团队必备配置。

依赖变更需同时说明实际用途并保持 `package.json` 与 `package-lock.json` 一致。未使用的调试 API 直依赖、没有兼容性依据的版本范围放宽，不因本地构建成功就自动入库。2026-10-04 已用仓库提交的依赖执行独立 `npm ci`，无需本机额外的 Devtools API 或 Sass 范围改动：前端 217 项、类型检查和生产构建通过；不含上述 IDEA 文件的 Java 17 后端 143 项测试通过。
