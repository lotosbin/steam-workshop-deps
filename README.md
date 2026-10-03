# Steam Workshop 依赖关系分析工具

用于抓取、存储和浏览 Steam 创意工坊物品的依赖与被依赖关系。目前以 **Project Zomboid（AppID 108600）** 为主，架构上不绑定具体游戏。

数据流：

```
Steam Workshop ──► Babashka 导入脚本 ──► Neo4j ──► FastAPI 后端 ──► Cytoscape.js 界面
  (Playwright 抓取)      (BFS 递归)       (图数据库)      (只读代理)
```

## 功能

- **依赖解析**：抓取 `Required items` 递归构建依赖链
- **反向依赖**：查询某个 mod 被哪些 mod 依赖
- **合集解析**：识别 Collection 页面，写入 `CONTAINS` 关系
- **作者图谱**：记录 `AUTHORED` / `ASSEMBLED` 关系
- **循环检测**：本地目录模式下检测可达范围内的循环依赖
- **定时导入**：Docker 容器每天 06:00 自动执行一次全量导入
- **图数据库存储**：所有数据落在 Neo4j，支持 Cypher 自由查询

## 技术栈

| 层 | 技术 |
|---|---|
| 抓取 | `@playwright/cli`（无头 Chromium，无需 Steam API Key） |
| 导入 | Babashka（Clojure 脚本，`*.bb.clj` + `src/steam_workshop/`） |
| 存储 | Neo4j 5（HTTP transaction endpoint） |
| 后端 | FastAPI + httpx（只读代理，隐藏 Neo4j 凭据） |
| 本地 CLI | Python 3.12（`main.py`，分析本地 workshop 目录） |
| 前端 | 原生 ES module + Cytoscape.js（`web/`，无需构建步骤） |
| 部署 | Docker / Docker Compose |

## 目录结构

```
steam-workshop-deps/
├── main.sh                          # 定时导入的执行清单（8 条 bb 导入命令）
├── Dockerfile                       # 定时导入容器镜像
├── Dockerfile.web                   # Web 应用容器镜像（REST API + 前端）
├── compose.yml                      # neo4j + importer + web 三个服务
├── docker/
│   ├── crontab                      # 调度定义（构建时注入 @SCHEDULE@）
│   ├── entrypoint.sh                # 写环境快照 + 启动时触发一次 + tail 日志
│   └── run-import.sh                # 加锁 → 加载快照 → 执行 main.sh → 记录日志
├── src/steam_workshop/              # 公共 namespace
│   ├── workshop.clj                 # 单页抓取与字段提取
│   ├── playwright_cli.clj           # playwright-cli 封装
│   ├── importer.clj                 # BFS 导入编排
│   ├── neo4j.clj                    # Cypher 语句与写入
│   └── dotenv.clj                   # .env 解析
├── *.bb.clj                         # 顶层 CLI 入口
├── main.py                          # 本地目录分析 CLI
├── backend/                         # FastAPI 只读代理（生产环境同时托管 web/）
└── web/                             # 前端单页应用（原生 ES module，无需构建）
    ├── index.html
    ├── src/                         # main / state / api / search / graph / detail / depth
    └── styles/                      # base / layout / search / graph / detail
```

## 快速开始

### 1. 依赖

```bash
# Babashka
brew install borkdude/brew/babashka

# Playwright 浏览器（首次使用）
npx @playwright/cli install-browser
```

> **浏览器渠道注意**：`playwright-cli` 默认使用 **chrome 渠道**（真实 Google Chrome，路径固定为 `/Applications/Google Chrome.app`），而不是自带 Chromium。若本机没有 Chrome，二选一：
> - 安装 Chrome：`npx @playwright/cli install-browser chrome`（需要管理员权限）
> - 或改用 Chromium：
>   ```bash
>   npx @playwright/cli install-browser chromium
>   mkdir -p ~/.playwright
>   echo '{ "browser": { "browserName": "chromium" } }' > ~/.playwright/cli.config.json
>   ```
> 定时导入容器已经在镜像内配置好此项，无需手动处理。

### 2. 配置 `.env`

复制 `.env.example` 并按需修改：

```env
NEO4J_AUTH=neo4j/你的密码
NEO4J_URI=bolt://localhost:7687
NEO4J_TX_URL=http://localhost:7474/db/neo4j/tx/commit
```

取值优先级：**系统环境变量 > `.env` 文件**。

### 3. 启动全部服务

```bash
docker compose up -d
```

| 服务 | 说明 | 端口 |
|---|---|---|
| `neo4j` | 图数据库 + Neo4j Browser | 7474 / 7687 |
| `importer` | 每天 06:00 执行 `main.sh` | — |
| `web` | Web 界面 + REST API | 8080 → 容器 80 |
| `cloudflared` | 把 `web` 发布到公网（需 `TUNNEL_TOKEN`） | — |

启动后浏览器打开 **<http://localhost:8080>**（宿主端口可用 `WEB_PORT` 覆盖）。

> 首次启动时数据库是空的，Web 界面会提示还没有任何游戏数据 —— 先按下面「导入数据」跑一次导入。

### 4. 本地开发（可选，不用容器）

```bash
# 后端 + 前端（FastAPI 会同时托管 web/ 静态页面）
uv sync --project backend
backend/.venv/bin/python -m uvicorn backend.app:app --reload --port 8000
```

需在仓库根目录启动，`backend/neo4j_client.py` 才会读到根目录的 `.env`。
此时访问 <http://127.0.0.1:8000> 即为完整应用，接口文档在 <http://127.0.0.1:8000/docs>。

前端是原生 ES module，**改完刷新页面即可**，没有构建步骤；仅需保证后端在跑（`/api/*` 同源）。

## 定时导入容器

`importer` 容器在**启动时立即触发一次**导入，之后每天 **06:00（Asia/Shanghai）** 再执行一次 `main.sh`。

```bash
docker compose logs -f importer           # 跟踪导入日志
docker compose restart importer           # 重启会立刻再触发一次导入
docker compose up -d --build importer     # 修改 main.sh 或 docker/ 后重新构建
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `RUN_ON_STARTUP` | `true` | 设为 `false` 则只在 06:00 执行，重启不再触发 |

行为说明：

- `main.sh` 内是 **8 条串行导入**，单条耗时数十分钟，整体可能持续数小时
- `run-import.sh` 用 `flock` 加锁，启动触发与定时触发**互斥**，不会重复跑
- 导入日志写入容器内 `/var/log/workshop-import.log`，由 entrypoint `tail` 转发到容器 stdout
- `--build-arg CRON_SCHEDULE="* * * * *"` 可构建测试镜像，验证调度链路而无需等到 06:00

## Babashka 脚本

### 批量导入（browse 列表递归）

```bash
bb steam_import_neo4j.bb.clj \
  --appid 108600 \
  --required-tag "Build 42" \
  --sort totaluniquesubscribers \
  --page 1 \
  --page-limit 10 \
  --max-depth 5 \
  --max-nodes 300
```

会从 browse 页抓取 seed（browse 页已改为 React SSR 渲染，seed 直接解析内联的 `window.SSR.renderContext` query cache，不再依赖列表 DOM），再去重、BFS 递归抓依赖。常用 `--sort`：

| 值 | 含义 |
|---|---|
| `lastupdated` | 最近更新（默认） |
| `totaluniquesubscribers` | 最多订阅 |
| `trend` | 热门 |

其他参数语义：

- `--page-limit`：从 `--page` 起连续抓取的页数，browse 请求固定带 `numperpage=30`
- `--max-nodes`：限制递归过程中**新增的依赖节点**数，不含初始 seed
- `--sort` 同时映射到 browse URL 的 `actualsort` 与 `browsesort`
- 写入 `Mod` 节点时记录 `imported_at`，1 小时内已导入的 mod 会被 BFS 跳过，避免重复抓取

### 指定用户 / 合集作为 seed

```bash
# 用户的 Workshop Items 页面
bb steam_import_neo4j.bb.clj \
  --user-workshop-url "https://steamcommunity.com/id/lotosbin/myworkshopfiles/?appid=108600" \
  --page 1 --page-limit 1 --max-depth 5 --max-nodes 300

# 用户的 Collections 页面
bb steam_import_neo4j.bb.clj \
  --user-workshop-url "https://steamcommunity.com/id/lotosbin/myworkshopfiles/?section=collections&appid=108600" \
  --user-workshop-section collections \
  --page 1 --page-limit 1 --max-depth 5 --max-nodes 300
```

`--user-workshop-url` 用于 `myworkshopfiles` **列表页**；若是 `sharedfiles/filedetails/?id=...` 这种**详情页**，请用下面的单条导入。

### 单条导入

```bash
bb steam_import_single_neo4j.bb.clj --id 3689745069
bb steam_import_single_neo4j.bb.clj --id 3689745069 --max-depth 10 --max-nodes 300
bb steam_import_single_neo4j.bb.clj --url "https://steamcommunity.com/sharedfiles/filedetails/?id=3624259825"
```

支持两类页面：

- **普通 Workshop item**：递归抓 `Required items` 依赖链，并补全标题、作者、封面、发布时间等
- **Collection 页面**：自动识别为 `Collection` 节点，提取条目写入 `(:Collection)-[:CONTAINS]->(:Mod)`，再递归抓这些条目的依赖

### 抓取单页信息（不写库）

```bash
bb steam_fetch_workshop_info.bb.clj --id 3688270372
bb steam_fetch_workshop_info.bb.clj --url "https://steamcommunity.com/sharedfiles/filedetails/?id=3688270372"
bb steam_fetch_workshop_info.bb.clj --session sw1 --id 3688270372   # 复用 session
```

默认输出 JSON。

### 查询节点关系

```bash
bb steam_query_neo4j.bb.clj --id 3689745069   # Mod
bb steam_query_neo4j.bb.clj --id 3624259825   # Collection
bb steam_query_neo4j.bb.clj --id lotosbin     # Author
```

自动识别 `Mod` / `Collection` / `Author`，分别返回作者与合集、`requires` / `required_by`、`contains`、`authored_mods` / `assembled_collections`。

## 本地目录分析（Python CLI）

不联网，直接分析本机已下载的 workshop 目录：

```bash
source .venv/bin/activate

workshop-deps tree    --workshop-dir "/path/to/steamapps/workshop/content/108600" --root "<内部id 或 published_id>"
workshop-deps reverse --workshop-dir "/path/to/steamapps/workshop/content/108600" --target "<内部id 或 published_id>"
workshop-deps cycles  --workshop-dir "/path/to/steamapps/workshop/content/108600" --root "<内部id 或 published_id>"
```

解析 `mod.info` / `workshop.txt` 中的 `require` 字段，在内存中构建依赖图。

## 数据模型（Neo4j）

### 节点

| 标签 | 主要属性 |
|---|---|
| `:Mod` | `id` `workshop_id` `title` `author` `author_id` `author_profile_url` `canonical_url` `preview_url` `posted` `updated` `file_size` `description` `obsolete` `imported_at` `source` |
| `:Collection` | `id` `workshop_id` `title` `author` `author_id` `canonical_url` `preview_url` `posted` `updated` `description` `page_type` `collection_item_ids` `linked_workshop_ids` |
| `:Author` | `id` `name` `profile_url` `source` |

### 关系

```
(:Mod)-[:REQUIRES]->(:Mod)
(:Collection)-[:CONTAINS]->(:Mod)
(:Author)-[:AUTHORED]->(:Mod)
(:Author)-[:ASSEMBLED]->(:Collection)
```

标题中包含 `obsolete` 或 `deprecate`（不区分大小写）的模组会被标记 `obsolete: true`，便于前端区别展示。

## Web 界面

单页应用，无路由、无框架：原生 ES module + Cytoscape.js（CDN），由 FastAPI 以 `StaticFiles` 托管，与 `/api/*` 同源。

| 控件 | 作用 |
|---|---|
| Game 下拉框 | 数据来自 `/api/games`，显示 `app_id (N mods)`；切换会清空搜索与画布 |
| Search | 输入 300ms 防抖后查 `/api/mods/search`，下拉最多 20 条（含 `[Obsolete]` 标记） |
| Depth 滑块 | 1–3，松开后按新深度重新拉取邻域图 |
| Layout 下拉框 | `dagre`（默认，分层） / `breadthfirst` / `cose` |
| 画布节点 | 点击打开右侧详情面板（标题、作者链接、发布/更新时间、文件大小、Steam 链接、预览图、obsolete 横幅） |
| 统计栏 | Nodes / Edges / Mods / Cycles（Cycles 为前端用 Tarjan SCC 在返回的子图上计算） |

实现要点：

- 节点大小设为 `width/height: label`，否则 Cytoscape 默认固定 30×30，标题会溢出节点框
- 稀疏图的自动 fit 会放得很大，已把自动缩放上限钳制在 1.3
- 标题里的 `Steam Workshop::` 前缀在展示与搜索匹配时都会被去掉
- 邻域图上限 200 节点，超出时顶部显示截断提示

## 公网发布（Cloudflare Tunnel）

用 Named Tunnel 把本机的 `web` 服务发布到固定域名。隧道运行在 compose 网络内，**只暴露 8080 上的 Web 应用**：Neo4j 与导入容器不经由隧道对外开放，Neo4j 凭据始终留在服务端。

### 1. 在 Cloudflare 后台创建隧道

1. 打开 **Zero Trust → Networks → Tunnels → Create a tunnel**，类型选 **Cloudflared**
2. 起个名字（如 `steam-workshop`）并保存
3. 复制页面给出的 **token**（`eyJhIjoi...` 开头的长串）
4. 进入 **Public Hostname → Add a public hostname**：
   - Subdomain / Domain：例如 `workshop` + `example.com`
   - Service 选 **HTTP**，URL 填 **`web:80`**（compose 服务名，已在本机验证网络内可达）
5. 保存

### 2. 把 token 写进 `.env`

```env
TUNNEL_TOKEN=eyJhIjoi...你的token...
```

### 3. 启动

```bash
docker compose up -d cloudflared
docker compose logs -f cloudflared     # 出现 Registered tunnel connection 即成功
```

随后访问 `https://workshop.example.com`。

| 环境变量 | 说明 |
|---|---|
| `TUNNEL_TOKEN` | Named Tunnel 凭据。留空时 cloudflared 立即退出（退出码 255），按 Docker 退避策略重试，不影响其它服务 |

安全提示：

- 只对外发布 `web`；**不要**把 `neo4j` 也加进 Public Hostname。Neo4j 目前在宿主机监听所有网卡的 7474/7687，若要收紧可把 compose 里的端口改为 `127.0.0.1:7474:7474` 与 `127.0.0.1:7687:7687`
- 应用**没有鉴权**：API 全是 GET，访客只能读图谱、无法改数据，但任何拿到域名的人都能查询
- 前端从 `unpkg.com` 加载 Cytoscape，访问者的网络需要能访问该 CDN

## 后端 API

`backend/` 是只读的 FastAPI 代理，避免把 Neo4j 凭据暴露给前端；生产环境同时托管 `web/` 静态文件。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| GET | `/api/games` | 游戏列表及各游戏 mod 数量 |
| GET | `/api/mods/search?q=&game=` | 标题前缀搜索（最多 20 条） |
| GET | `/api/mods/{workshop_id}` | 单个 mod 详情 |
| GET | `/api/graph/{workshop_id}?depth=1..3` | 邻域依赖图（上限 200 节点，去重） |
| GET | `/api/graph/path?from=&to=` | 两个 mod 间最短依赖路径 |

> 路由顺序有要求：`/api/graph/path` 必须声明在 `/api/graph/{workshop_id}` 之前，否则会被当作 `workshop_id="path"` 匹配掉。

## 环境说明

- **Neo4j Community 版**：只支持单个数据库，且没有细粒度权限（`GRANT ROLE` 等为 Enterprise 功能）。若需要只读对外访问，只能用 `server.databases.read_only=neo4j` 配置项（需重启生效，且会同时禁止导入容器写入）
- **Playwright 抓取**：依赖 Steam 页面结构，目前存在两套页面：详情页（`sharedfiles/filedetails`）仍是旧版 DOM，走 `workshop.clj` 的选择器；列表页（`/workshop/browse/`、`myworkshopfiles`）已改为 React SSR，seed 提取优先解析内联的 `window.SSR.renderContext`，旧版 DOM 选择器与 href 正则只作兜底。Steam 再次改版时优先检查这两条路径
- **导入耗时**：单条导入需要数十分钟，全量 `main.sh` 可能持续数小时，建议交给定时容器执行

## 路线图

- [x] 单页抓取与字段提取
- [x] 从 browse / 用户页面 / 合集导入
- [x] 依赖递归导入与作者图谱
- [x] obsolete 标记
- [x] Docker 定时导入容器
- [x] 后端只读 API
- [x] 前端图可视化（搜索 / 邻域图 / 详情面板 / 深度与布局切换）
- [x] 单容器 Docker 部署（API + 前端，宿主 8080）
- [ ] URL 状态（可分享链接）与主题细化
- [ ] 其他游戏适配

---

**最后更新**：2026-10-03
