# 一个可以查看steam 上游戏mod依赖关系的工具, 目前支持Project Zomboid, 其他游戏后续添加
# 目前功能:
# - 查看模组依赖关系
# - 查看模组被哪些模组依赖, 以及依赖关系图

# Steam Workshop 依赖关系分析工具

## 项目概述
用于查找和浏览 Steam Workshop 创意工坊物品的依赖和被依赖关系的工具。

## 核心功能

### 1. 依赖关系解析
- 解析 Workshop 物品的 `workshop.txt` 配置文件
- 提取 `dependencies` 和 `required_items` 字段
- 支持递归解析多层依赖关系

### 2. 关系可视化
- 依赖树展示（Tree View）
- 依赖图展示（Graph View）
- 反向依赖查询（谁依赖了这个 mod）

### 3. 数据源支持
- 本地 Workshop 目录扫描
- Steam Web API 查询
- 缓存机制减少 API 调用

## Babashka 脚本

### 获取单个 Workshop 信息
```bash
bb steam_fetch_workshop_info.bb.clj --id 3688270372
bb steam_fetch_workshop_info.bb.clj --url "https://steamcommunity.com/sharedfiles/filedetails/?id=3688270372&searchtext="
bb steam_fetch_workshop_info.bb.clj --session sw1 --id 3688270372
```

默认输出 JSON。
脚本通过 `@playwright/cli` 抓取 Steam Community 页面，不依赖 Steam API Key。
首次使用如果本机没有浏览器运行时，执行 `npx @playwright/cli install-browser`。
项目通过 `bb.edn` 暴露 `src/` 下的公共 namespace，单条抓取、playwright-cli 调用和 `.env` 解析分别抽在：
`src/steam_workshop/workshop.clj`
`src/steam_workshop/playwright_cli.clj`
`src/steam_workshop/dotenv.clj`
Neo4j 写入和导入流程抽在：
`src/steam_workshop/neo4j.clj`
`src/steam_workshop/importer.clj`

## 技术架构

### 架构图
```
┌─────────────────────────────────────────────────────────┐
│                    用户界面层 (UI)                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  依赖树视图   │  │  依赖图视图   │  │  搜索界面     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                   业务逻辑层 (Service)                    │
│  ┌──────────────────────────────────────────────────┐  │
│  │         DependencyAnalyzer (依赖分析器)            │  │
│  │  - buildDependencyTree()                         │  │
│  │  - findReverseDependencies()                     │  │
│  │  - detectCircularDependencies()                  │  │
│  └──────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────┐  │
│  │         WorkshopItemResolver (物品解析器)          │  │
│  │  - resolveItem(id) -> WorkshopItem               │  │
│  │  - parseWorkshopTxt()                            │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                   数据访问层 (Data)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ LocalScanner │  │ SteamWebAPI  │  │ CacheManager │  │
│  │ (本地扫描)    │  │ (API查询)     │  │ (缓存管理)    │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                      数据源                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Workshop目录  │  │ Steam API    │  │ 本地数据库    │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## 数据模型

### WorkshopItem
```swift
struct WorkshopItem: Identifiable, Codable {
    let id: String              // Workshop ID
    let title: String           // 物品标题
    let description: String?    // 描述
    let dependencies: [String]  // 依赖的 Workshop ID 列表
    let tags: [String]          // 标签
    let author: String?         // 作者
    let lastUpdated: Date?      // 最后更新时间
    let localPath: String?      // 本地路径（如果已下载）
}
```

### DependencyNode
```swift
struct DependencyNode: Identifiable {
    let id: String
    let item: WorkshopItem
    var children: [DependencyNode]  // 依赖项
    var depth: Int                  // 依赖深度
    var isCircular: Bool            // 是否存在循环依赖
}
```

### DependencyGraph
```swift
struct DependencyGraph {
    var nodes: [String: WorkshopItem]           // 所有节点
    var edges: [String: [String]]               // 依赖关系边
    var reverseEdges: [String: [String]]        // 反向依赖边

    func findPath(from: String, to: String) -> [String]?
    func detectCycles() -> [[String]]
}
```

## 核心算法

### 1. 依赖树构建
```swift
class DependencyAnalyzer {
    func buildDependencyTree(
        rootId: String,
        maxDepth: Int = 10
    ) async throws -> DependencyNode {
        var visited = Set<String>()
        return try await buildNode(
            id: rootId,
            depth: 0,
            maxDepth: maxDepth,
            visited: &visited
        )
    }

    private func buildNode(
        id: String,
        depth: Int,
        maxDepth: Int,
        visited: inout Set<String>
    ) async throws -> DependencyNode {
        // 检测循环依赖
        let isCircular = visited.contains(id)
        visited.insert(id)

        // 解析物品信息
        let item = try await resolver.resolveItem(id: id)

        // 递归构建子节点
        var children: [DependencyNode] = []
        if depth < maxDepth && !isCircular {
            for depId in item.dependencies {
                let child = try await buildNode(
                    id: depId,
                    depth: depth + 1,
                    maxDepth: maxDepth,
                    visited: &visited
                )
                children.append(child)
            }
        }

        return DependencyNode(
            id: id,
            item: item,
            children: children,
            depth: depth,
            isCircular: isCircular
        )
    }
}
```

### 2. 反向依赖查询
```swift
func findReverseDependencies(
    itemId: String
) async throws -> [WorkshopItem] {
    // 扫描所有本地 Workshop 物品
    let allItems = try await localScanner.scanAllItems()

    // 查找依赖了目标物品的所有物品
    return allItems.filter { item in
        item.dependencies.contains(itemId)
    }
}
```

### 3. 循环依赖检测
```swift
func detectCircularDependencies(
    itemId: String
) async throws -> [[String]] {
    var cycles: [[String]] = []
    var visited = Set<String>()
    var path: [String] = []

    func dfs(id: String) async throws {
        if path.contains(id) {
            // 发现循环
            let cycleStart = path.firstIndex(of: id)!
            let cycle = Array(path[cycleStart...]) + [id]
            cycles.append(cycle)
            return
        }

        if visited.contains(id) { return }
        visited.insert(id)
        path.append(id)

        let item = try await resolver.resolveItem(id: id)
        for depId in item.dependencies {
            try await dfs(id: depId)
        }

        path.removeLast()
    }

    try await dfs(id: itemId)
    return cycles
}
```

## 数据源实现

### 1. 本地扫描器
```swift
class LocalWorkshopScanner {
    let workshopPath: String

    func scanAllItems() async throws -> [WorkshopItem] {
        let fm = FileManager.default
        let contents = try fm.contentsOfDirectory(atPath: workshopPath)

        var items: [WorkshopItem] = []
        for itemId in contents {
            let itemPath = "\(workshopPath)/\(itemId)"
            if let item = try? parseWorkshopItem(at: itemPath) {
                items.append(item)
            }
        }
        return items
    }

    func parseWorkshopItem(at path: String) throws -> WorkshopItem {
        // 解析 workshop.txt 或 mod.info
        let configPath = "\(path)/workshop.txt"
        let content = try String(contentsOfFile: configPath)
        return try parseWorkshopConfig(content, itemPath: path)
    }
}
```

### 2. Steam Web API 客户端
```swift
class SteamWebAPIClient {
    let apiKey: String
    let baseURL = "https://api.steampowered.com"

    func getPublishedFileDetails(
        itemIds: [String]
    ) async throws -> [WorkshopItem] {
        let endpoint = "\(baseURL)/ISteamRemoteStorage/GetPublishedFileDetails/v1/"

        var request = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "POST"

        // 构建请求参数
        var params = ["key": apiKey, "itemcount": "\(itemIds.count)"]
        for (index, id) in itemIds.enumerated() {
            params["publishedfileids[\(index)]"] = id
        }

        // 发送请求并解析响应
        let (data, _) = try await URLSession.shared.data(for: request)
        return try parseAPIResponse(data)
    }
}
```

### 3. 缓存管理器
```swift
class WorkshopCacheManager {
    private let cache = NSCache<NSString, WorkshopItem>()
    private let diskCachePath: String

    func get(id: String) -> WorkshopItem? {
        // 先查内存缓存
        if let item = cache.object(forKey: id as NSString) {
            return item
        }

        // 再查磁盘缓存
        return loadFromDisk(id: id)
    }

    func set(item: WorkshopItem) {
        cache.setObject(item, forKey: item.id as NSString)
        saveToDisk(item: item)
    }
}
```

## UI 实现方案

### 1. SwiftUI (推荐)
```swift
struct DependencyTreeView: View {
    @StateObject var viewModel: DependencyViewModel

    var body: some View {
        NavigationView {
            List {
                if let root = viewModel.rootNode {
                    DependencyNodeRow(node: root)
                }
            }
            .navigationTitle("依赖关系")
            .searchable(text: $viewModel.searchText)
        }
    }
}

struct DependencyNodeRow: View {
    let node: DependencyNode
    @State private var isExpanded = true

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            ForEach(node.children) { child in
                DependencyNodeRow(node: child)
            }
        } label: {
            HStack {
                Image(systemName: node.isCircular ? "exclamationmark.triangle" : "cube")
                    .foregroundColor(node.isCircular ? .red : .blue)
                Text(node.item.title)
                Spacer()
                Text("深度: \(node.depth)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }
}
```

### 2. 依赖图可视化（使用 GraphViz 或 D3.js）
```swift
struct DependencyGraphView: View {
    let graph: DependencyGraph

    var body: some View {
        // 使用 WebView 嵌入 D3.js 可视化
        // 或使用 SwiftUI Canvas 自定义绘制
        GraphCanvas(graph: graph)
    }
}
```

## 配置文件解析

### workshop.txt 格式
```
version="1"
id="2876234567"
title="My Mod"
description="Description here"
tags={
    "Mod"
    "Multiplayer"
}
visibility="public"
```

### mod.info 格式（Project Zomboid）
```
name=My Mod
id=MyModID
description=Description here
require=RequiredModID1,RequiredModID2
```

## 技术栈建议

### 后端/CLI 工具
- **语言**: Swift (命令行工具) 或 Python (脚本)
- **数据存储**: SQLite (本地缓存)
- **HTTP 客户端**: URLSession (Swift) 或 requests (Python)

### 前端 GUI
- **macOS**: SwiftUI + AppKit
- **跨平台**: Electron + React + D3.js
- **Web**: Next.js + React + Cytoscape.js

### 图形可视化
- **D3.js**: 强大的数据可视化库
- **Cytoscape.js**: 专门用于图形网络可视化
- **GraphViz**: 自动布局的图形工具

## 项目结构

```
steam-workshop-deps/
├── README.md
├── Package.swift (如果使用 Swift)
├── requirements.txt (如果使用 Python)
├── src/
│   ├── models/
│   │   ├── WorkshopItem.swift
│   │   ├── DependencyNode.swift
│   │   └── DependencyGraph.swift
│   ├── services/
│   │   ├── DependencyAnalyzer.swift
│   │   ├── WorkshopItemResolver.swift
│   │   └── CacheManager.swift
│   ├── data/
│   │   ├── LocalWorkshopScanner.swift
│   │   ├── SteamWebAPIClient.swift
│   │   └── ConfigParser.swift
│   └── ui/
│       ├── DependencyTreeView.swift
│       ├── DependencyGraphView.swift
│       └── SearchView.swift
├── tests/
│   ├── DependencyAnalyzerTests.swift
│   └── ConfigParserTests.swift
└── resources/
    └── sample_workshop.txt
```

## 使用示例

### CLI 使用
```bash
# 分析单个物品的依赖
$ workshop-deps analyze 2876234567

# 查找反向依赖
$ workshop-deps reverse-deps 2876234567

# 检测循环依赖
$ workshop-deps check-cycles 2876234567

# 导出依赖图
$ workshop-deps export-graph 2876234567 --format=dot
```

### API 使用
```swift
let analyzer = DependencyAnalyzer()

// 构建依赖树
let tree = try await analyzer.buildDependencyTree(rootId: "2876234567")

// 查找反向依赖
let reverseDeps = try await analyzer.findReverseDependencies(itemId: "2876234567")

// 检测循环依赖
let cycles = try await analyzer.detectCircularDependencies(itemId: "2876234567")
```

## 扩展功能

### 1. 依赖冲突检测
- 检测版本不兼容
- 检测互斥的 mod

### 2. 批量操作
- 批量下载依赖
- 批量更新 mod

### 3. 依赖推荐
- 基于依赖关系推荐相关 mod
- 分析热门依赖组合

### 4. 导出功能
- 导出为 JSON/YAML
- 导出为图片（PNG/SVG）
- 导出为 Markdown 文档

## 性能优化

1. **并发处理**: 使用 async/await 并发解析多个物品
2. **缓存策略**: 多级缓存（内存 + 磁盘）
3. **增量更新**: 只更新变化的物品
4. **懒加载**: 按需加载深层依赖

## 下一步实施计划

1. ✅ 完成架构设计文档
2. ⬜ 实现数据模型
3. ⬜ 实现本地扫描器
4. ⬜ 实现依赖分析器
5. ⬜ 实现基础 CLI 工具
6. ⬜ 实现 GUI 界面
7. ⬜ 添加 Steam Web API 支持
8. ⬜ 添加缓存机制
9. ⬜ 添加可视化功能
10. ⬜ 编写测试用例

---

**作者**: Claude
**最后更新**: 2025-01-29


- [] 从 https://steamcommunity.com/workshop/browse/?appid=108600&requiredtags%5B0%5D=Build+42&actualsort=lastupdated&browsesort=lastupdated&p=1 获取workshop item 列表,递归分析每个item的require item 并添加到neo4j

### Babashka 一键导入到 Neo4j（MVP）
```bash
# 前置：安装 babashka(bb)
# playwright-cli 首次使用需要安装浏览器：
#   npx @playwright/cli install-browser
# .env 示例：
#   NEO4J_AUTH=neo4j/你的密码
#   NEO4J_URI=bolt://localhost:7687
#   NEO4J_TX_URL=http://localhost:7474/db/neo4j/tx/commit

bb steam_import_neo4j.bb.clj \
  --appid 108600 \
  --required-tag "Build 42" \
  --page 1 \
  --page-limit 10 \
  --max-depth 5 \
  --max-nodes 300
```

导入脚本会自动读取项目根目录下的 `.env`，并在整个导入过程中复用一个 `playwright-cli` browser session。
`--page-limit` 用于从 `--page` 开始连续抓取多页 workshop browse seed，再统一去重后做 BFS。当前默认抓前 `10` 页，且 browse 请求会带 `numperpage=30`。

### 导入单个 Workshop 到 Neo4j
```bash
bb steam_import_single_neo4j.bb.clj --id 3689745069
bb steam_import_single_neo4j.bb.clj --id 3689745069 --max-depth 10 --max-nodes 300
```

单条导入现在也会递归抓取 `Required items` 依赖链上的每一个 item，并为每个节点补全标题、作者、封面、发布时间等信息。

顶层 `*.bb.clj` 只负责 CLI 参数解析；抓取、导入、Neo4j 写入等具体逻辑统一放在 `src/steam_workshop/`。

### 查询单个 Workshop 在 Neo4j 中的节点和边
```bash
bb steam_query_neo4j.bb.clj --id 3689745069
```

输出包含：
- 节点是否存在
- 节点属性
- `requires` 出边列表
- `required_by` 入边列表

- [ ] 获取单个workshop信息 https://steamcommunity.com/sharedfiles/filedetails/?id=3688270372&searchtext=
