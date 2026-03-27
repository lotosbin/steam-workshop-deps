# Research Findings

记录在项目开发过程中发现的重要信息、技术细节和调研结果。

## Initial Project Analysis

### 项目概述
- 项目名称：Steam Workshop 依赖关系分析工具
- 主要功能：分析 Steam 创意工坊物品的依赖和被依赖关系
- 当前支持的游戏：Project Zomboid
- 计划扩展支持：其他 Steam 游戏

### 核心功能点
1. 依赖关系解析
2. 依赖关系可视化（树状图和图形）
3. 反向依赖查询
4. 循环依赖检测

### 技术架构
- 用户界面层：计划使用 SwiftUI
- 业务逻辑层：依赖分析器、物品解析器
- 数据访问层：本地扫描器、Steam Web API、缓存管理器

### 数据模型
- WorkshopItem：工作坊物品数据模型
- DependencyNode：依赖节点模型
- DependencyGraph：依赖图模型

## Technical Research

### 需要进一步研究的领域

#### 1. Steam Web API
- 需要了解如何获取工作坊物品详情
- API调用限制和速率限制
- 认证方式和API密钥申请流程

#### 2. 配置文件格式
- workshop.txt 文件格式规范
- 不同游戏可能有不同的配置文件格式
- Project Zomboid 的 mod.info 格式

#### 3. 依赖解析算法
- 如何高效地构建依赖树
- 循环依赖检测算法
- 反向依赖查询优化

#### 4. 可视化方案
- 图形可视化库选择（D3.js, Cytoscape.js, GraphViz）
- SwiftUI 中的图形渲染能力
- 大型依赖图的性能优化

## Game-Specific Research

### Project Zomboid Modding
- Mod 文件结构
- 依赖声明方式
- 常见的依赖模式

### 其他游戏适配
- 不同游戏的配置文件差异
- 统一的解析接口设计
- 扩展性考虑

## Implementation Options

### 开发语言选择
- Swift (原生 macOS 应用，命令行工具)
- Python (脚本化，跨平台)
- 考虑混合方案：Python 后端 + SwiftUI 前端

### 数据存储方案
- SQLite 用于本地缓存
- JSON 文件用于配置
- 内存缓存策略

### UI 框架选择
- SwiftUI (macOS 原生)
- 可能需要考虑跨平台选项