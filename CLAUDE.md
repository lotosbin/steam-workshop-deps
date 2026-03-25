# Steam Workshop Dependencies

Steam Workshop 依赖关系分析工具，主要支持 Project Zomboid。

## 项目概述

用于查找和浏览 Steam Workshop 创意工坊物品的依赖和被依赖关系的工具。

## 技术栈

- **Clojure**: Babashka (`.bb.clj`) 脚本，主要业务逻辑
- **Python**: CLI 入口 (`main.py`)
- **Playwright CLI**: 网页抓取 Steam 社区页面
- **Neo4j**: 图数据库存储

## 项目结构

```
steam-workshop-deps/
├── main.py                      # Python CLI 入口
├── pyproject.toml               # Python 依赖配置
├── bb.edn                       # Babashka 配置
├── .env                         # Neo4j/Steam API 配置
├── src/steam_workshop/          # Clojure 公共 namespace
│   ├── workshop.clj             # Steam workshop 抓取逻辑
│   ├── playwright_cli.clj       # Playwright CLI 封装
│   ├── dotenv.clj               # .env 解析
│   ├── neo4j.clj                # Neo4j 操作
│   └── importer.clj            # 导入逻辑
├── web/                         # Web MVP 静态页面
└── *.bb.clj                     # 顶层 Babashka 脚本
```

## 环境配置

### 必需依赖

- `bb` (Babashka)
- Python 3.12+
- Node.js (用于 playwright-cli)

### 环境变量 (.env)

```env
NEO4J_AUTH=neo4j/你的密码
NEO4J_URI=bolt://localhost:7687
NEO4J_TX_URL=http://localhost:7474/db/neo4j/tx/commit
STEAM_API_KEY=你的Steam API Key  # 可选
```

### 首次使用

```bash
# 安装 playwright 浏览器
npx @playwright/cli install-browser
```

## CLI 使用

### Python CLI

```bash
# 激活虚拟环境
source .venv/bin/activate

# 输出依赖树
workshop-deps tree --workshop-dir "/path/to/workshop/content/108600" --root "mod_id"

# 输出反向依赖
workshop-deps reverse --workshop-dir "/path/to/workshop/content/108600" --target "mod_id"

# 检测循环依赖
workshop-deps cycles --workshop-dir "/path/to/workshop/content/108600" --root "mod_id"
```

### Babashka CLI

```bash
# 获取单个 Workshop 信息
bb steam_fetch_workshop_info.bb.clj --id 3688270372

# 导入到 Neo4j
bb steam_import_neo4j.bb.clj \
  --appid 108600 \
  --required-tag "Build 42" \
  --sort totaluniquesubscribers \
  --page 1 \
  --page-limit 10 \
  --max-depth 5 \
  --max-nodes 300

# 导入单个 Item
bb steam_import_single_neo4j.bb.clj --id 3689745069

# 查询 Neo4j
bb steam_query_neo4j.bb.clj --id 3689745069
```

### 常用参数

- `--sort`: `lastupdated` (最近更新) | `totaluniquesubscribers` (最多订阅) | `trend` (热门)
- `--appid`: Steam App ID (Project Zomboid = 108600)
- `--user-workshop-url`: 指定用户的 Workshop 页面
- `--user-workshop-section`: `collections` 提取合集列表

## 核心功能

1. **依赖树解析**: 解析 `mod.info` / `workshop.txt`
2. **反向依赖查询**: 查找依赖某 mod 的所有 mod
3. **循环依赖检测**: 检测可达范围内的循环
4. **Steam 网页抓取**: 通过 Playwright CLI 抓取 Steam 社区页面
5. **Neo4j 图数据库**: 存储和查询依赖关系图

## 注意事项

- 模组标题包含 `obsolete` 或 `deprecate` (不区分大小写) 会标记为 obsolete
- Steam App ID: Project Zomboid = 108600
