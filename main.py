from __future__ import annotations

import argparse
import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import requests


STEAM_WEB_API_BASE = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
STEAM_API_KEY_ENV = "STEAM_API_KEY"


@dataclass(frozen=True)
class WorkshopItem:
    # Zomboid mod.info 里的内部 id（require= 引用的通常是这个）
    internal_id: str
    # Steam PublishedFileId（workshop 文件夹名，通常是数字）
    published_id: str
    title: str
    description: Optional[str]
    dependencies: List[str]  # internal_id 列表
    author: Optional[str] = None


@dataclass
class TreeNode:
    internal_id: str
    title: str
    published_id: Optional[str]
    children: List["TreeNode"]
    depth: int
    is_circular: bool = False
    missing: bool = False


def _strip_quotes(s: str) -> str:
    s = s.strip()
    if len(s) >= 2 and ((s[0] == s[-1] == '"') or (s[0] == s[-1] == "'")):
        return s[1:-1]
    return s


def _parse_kv_text(text: str) -> Dict[str, str]:
    """
    解析类似:
      name=My Mod
      require=Id1,Id2
    的 key=value 文本。
    """
    out: Dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("#") or line.startswith("//"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def _parse_list_like(value: str) -> List[str]:
    """
    尽量兼容 workshop.txt / keys 中可能出现的数组/集合形式。
    例如:
      dependencies={"123","456"}
      required_items=123,456
      required_items=["123","456"]
    """
    v = value.strip()
    # 去掉外围包裹
    if (v.startswith("{") and v.endswith("}")) or (v.startswith("[") and v.endswith("]")):
        v = v[1:-1].strip()
    # 优先提取引号内内容
    quoted = re.findall(r'"([^"]+)"', v)
    if quoted:
        return [x.strip() for x in quoted if x.strip()]
    # 再提取简单 token（数字/字母/常见符号）
    tokens = re.findall(r"[0-9A-Za-z_.-]+", v)
    return [t.strip() for t in tokens if t.strip()]


def parse_zomboid_mod_info(mod_info_path: Path, published_id: str) -> Optional[WorkshopItem]:
    if not mod_info_path.exists():
        return None
    try:
        content = mod_info_path.read_text(errors="ignore")
    except Exception:
        return None

    kv = _parse_kv_text(content)
    internal_id = kv.get("id")
    if not internal_id:
        return None
    internal_id = _strip_quotes(internal_id)

    title = kv.get("name") or kv.get("title") or internal_id
    title = _strip_quotes(title) if title else internal_id

    description = kv.get("description")
    if description:
        description = _strip_quotes(description)

    author = kv.get("author")
    if author:
        author = _strip_quotes(author)

    deps: List[str] = []
    # Zomboid 常见字段: require=Id1,Id2
    for key in ("require", "dependencies", "required_items"):
        if key not in kv:
            continue
        raw = kv[key]
        deps.extend(_parse_list_like(raw))

    # 去重但保持顺序
    seen: Set[str] = set()
    deduped: List[str] = []
    for d in deps:
        if d in seen:
            continue
        seen.add(d)
        deduped.append(d)

    return WorkshopItem(
        internal_id=internal_id,
        published_id=str(published_id),
        title=title,
        description=description,
        dependencies=deduped,
        author=author,
    )


def parse_workshop_txt(workshop_txt_path: Path, published_id: str) -> Optional[WorkshopItem]:
    """
    workshop.txt 格式在不同游戏可能差异很大，这里做一个“尽量解析”的 MVP：
    - id/title/description/author 若存在则解析
    - dependencies/required_items 尝试解析为列表
    """
    if not workshop_txt_path.exists():
        return None
    try:
        content = workshop_txt_path.read_text(errors="ignore")
    except Exception:
        return None

    kv = _parse_kv_text(content)
    internal_id = kv.get("id")
    if internal_id:
        internal_id = _strip_quotes(internal_id)
    else:
        return None

    title = kv.get("title") or kv.get("name") or internal_id
    title = _strip_quotes(title) if title else internal_id

    description = kv.get("description")
    if description:
        description = _strip_quotes(description)

    author = kv.get("author")
    if author:
        author = _strip_quotes(author)

    deps: List[str] = []
    for key in ("dependencies", "required_items", "require"):
        if key not in kv:
            continue
        deps.extend(_parse_list_like(kv[key]))

    seen: Set[str] = set()
    deduped: List[str] = []
    for d in deps:
        if d in seen:
            continue
        seen.add(d)
        deduped.append(d)

    return WorkshopItem(
        internal_id=internal_id,
        published_id=str(published_id),
        title=title,
        description=description,
        dependencies=deduped,
        author=author,
    )


def scan_local_workshop(workshop_dir: Path) -> Tuple[Dict[str, WorkshopItem], Dict[str, str]]:
    """
    扫描:
      .../steamapps/workshop/content/<appId>/<publishedFileId>/
    每个子目录尝试解析 mod.info / workshop.txt。

    返回:
      (items_by_internal_id, internal_id_by_published_id)
    """
    if not workshop_dir.exists():
        raise FileNotFoundError(f"workshop_dir 不存在: {workshop_dir}")

    items_by_internal_id: Dict[str, WorkshopItem] = {}
    internal_id_by_published_id: Dict[str, str] = {}

    for child in sorted(workshop_dir.iterdir(), key=lambda p: p.name):
        if not child.is_dir():
            continue
        published_id = child.name
        item = parse_zomboid_mod_info(child / "mod.info", published_id)
        if not item:
            item = parse_workshop_txt(child / "workshop.txt", published_id)
        if not item:
            continue
        items_by_internal_id[item.internal_id] = item
        internal_id_by_published_id[item.published_id] = item.internal_id

    return items_by_internal_id, internal_id_by_published_id


def resolve_mod_id(
    root: str,
    items_by_internal_id: Dict[str, WorkshopItem],
    internal_id_by_published_id: Dict[str, str],
) -> WorkshopItem:
    if root in items_by_internal_id:
        return items_by_internal_id[root]
    if root in internal_id_by_published_id:
        internal_id = internal_id_by_published_id[root]
        return items_by_internal_id[internal_id]
    raise ValueError(
        f"无法解析 root={root}：本地已扫描到的内部 id 数量={len(items_by_internal_id)}，"
        f"published_id 数量={len(internal_id_by_published_id)}。"
    )


def build_dependency_tree(
    root_internal_id: str,
    items_by_internal_id: Dict[str, WorkshopItem],
    max_depth: int,
) -> TreeNode:
    def rec(current_id: str, depth: int, path_on_stack: List[str]) -> TreeNode:
        if current_id in path_on_stack:
            # 命中环：停止继续展开
            item = items_by_internal_id.get(current_id)
            return TreeNode(
                internal_id=current_id,
                title=item.title if item else current_id,
                published_id=item.published_id if item else None,
                children=[],
                depth=depth,
                is_circular=True,
                missing=not bool(item),
            )

        item = items_by_internal_id.get(current_id)
        if not item:
            return TreeNode(
                internal_id=current_id,
                title=f"(missing {current_id})",
                published_id=None,
                children=[],
                depth=depth,
                missing=True,
            )

        if depth >= max_depth:
            return TreeNode(
                internal_id=item.internal_id,
                title=item.title,
                published_id=item.published_id,
                children=[],
                depth=depth,
                is_circular=False,
                missing=False,
            )

        node = TreeNode(
            internal_id=item.internal_id,
            title=item.title,
            published_id=item.published_id,
            children=[],
            depth=depth,
        )
        new_path = path_on_stack + [current_id]
        for dep_id in item.dependencies:
            node.children.append(rec(dep_id, depth + 1, new_path))
        return node

    return rec(root_internal_id, 0, [])


def print_tree(node: TreeNode, *, indent: str = "", is_last: bool = True) -> None:
    prefix = indent + ("└─ " if is_last else "├─ ")
    flags: List[str] = []
    if node.is_circular:
        flags.append("cycle")
    if node.missing:
        flags.append("missing")
    flag_txt = f" [{','.join(flags)}]" if flags else ""
    pub_txt = f" pub={node.published_id}" if node.published_id else ""
    print(f"{prefix}{node.title} (id={node.internal_id}{pub_txt}){flag_txt}")

    next_indent = indent + ("   " if is_last else "│  ")
    for i, child in enumerate(node.children):
        print_tree(child, indent=next_indent, is_last=(i == len(node.children) - 1))


def build_reverse_edges(items_by_internal_id: Dict[str, WorkshopItem]) -> Dict[str, List[str]]:
    reverse: Dict[str, List[str]] = {}
    for item in items_by_internal_id.values():
        for dep in item.dependencies:
            reverse.setdefault(dep, []).append(item.internal_id)
    return reverse


def find_cycles_from_root(
    root_internal_id: str,
    items_by_internal_id: Dict[str, WorkshopItem],
) -> List[List[str]]:
    cycles: List[List[str]] = []
    stack: List[str] = []
    on_path: Set[str] = set()

    def dfs(current_id: str) -> None:
        if current_id in on_path:
            # 生成一个闭环表示
            start_idx = stack.index(current_id)
            cycle = stack[start_idx:] + [current_id]
            cycles.append(cycle)
            return
        item = items_by_internal_id.get(current_id)
        if not item:
            return
        on_path.add(current_id)
        stack.append(current_id)
        for dep_id in item.dependencies:
            dfs(dep_id)
        stack.pop()
        on_path.remove(current_id)

    dfs(root_internal_id)

    # 去重：用整条路径的元组表示（MVP 层面即可）
    seen: Set[Tuple[str, ...]] = set()
    unique: List[List[str]] = []
    for c in cycles:
        t = tuple(c)
        if t in seen:
            continue
        seen.add(t)
        unique.append(c)
    return unique


def get_dependent_mods_from_steam_web_api(target_mod_id: str) -> List[str]:
    """
    使用 Steam Web API 的 dependents 字段做“反向依赖”的一个补充能力（MVP）。
    注意：需要环境变量 `STEAM_API_KEY`。
    """
    api_key = os.getenv(STEAM_API_KEY_ENV, "").strip()
    if not api_key:
        raise RuntimeError(f"缺少 Steam API Key：请设置环境变量 {STEAM_API_KEY_ENV}")

    params = {
        "key": api_key,
        "itemcount": 1,
        "publishedfileids[0]": target_mod_id,
        "includemetadata": True,
    }
    resp = requests.get(STEAM_WEB_API_BASE, params=params, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    dependent_mods: List[str] = []
    if "publishedfiledetails" in data:
        for item in data["publishedfiledetails"]:
            if "dependents" in item:
                dependent_mods.extend(item["dependents"])
    return dependent_mods


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="workshop-deps",
        description="Steam Workshop 依赖关系分析 MVP（目前重点支持 Project Zomboid 的本地 mod.info 解析）。",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_tree = sub.add_parser("tree", help="输出依赖树（root 的依赖向下展开）")
    p_tree.add_argument("--workshop-dir", required=True, type=Path, help=".../steamapps/workshop/content/<appId>")
    p_tree.add_argument("--root", required=True, help="内部 id（优先）或 published_id（子目录名，数字）")
    p_tree.add_argument("--max-depth", type=int, default=10, help="最大展开层级（默认 10）")

    p_rev = sub.add_parser("reverse", help="输出反向依赖（谁依赖了 target）")
    p_rev.add_argument("--workshop-dir", required=True, type=Path)
    p_rev.add_argument("--target", required=True, help="内部 id 或 published_id")
    p_rev.add_argument("--limit", type=int, default=200, help="最多输出数量（默认 200）")
    p_rev.add_argument("--with-steam-web", action="store_true", help="额外查询 Steam Web API 的 dependents（需要 STEAM_API_KEY）")

    p_cycles = sub.add_parser("cycles", help="从 root 开始检测可达范围内的循环依赖")
    p_cycles.add_argument("--workshop-dir", required=True, type=Path)
    p_cycles.add_argument("--root", required=True)

    args = parser.parse_args(argv)

    if args.cmd == "tree":
        items_by_internal_id, internal_id_by_published_id = scan_local_workshop(args.workshop_dir)
        root_item = resolve_mod_id(args.root, items_by_internal_id, internal_id_by_published_id)
        tree = build_dependency_tree(root_item.internal_id, items_by_internal_id, args.max_depth)
        print_tree(tree)
        return 0

    if args.cmd == "reverse":
        items_by_internal_id, internal_id_by_published_id = scan_local_workshop(args.workshop_dir)
        target_item = resolve_mod_id(args.target, items_by_internal_id, internal_id_by_published_id)
        reverse_edges = build_reverse_edges(items_by_internal_id)
        dependers = reverse_edges.get(target_item.internal_id, [])

        print(f"Local reverse-deps: {len(dependers)} 个（target={target_item.title} id={target_item.internal_id}）")
        for internal_id in dependers[: args.limit]:
            item = items_by_internal_id.get(internal_id)
            if item:
                print(f"- {item.title} (id={item.internal_id} pub={item.published_id})")
            else:
                print(f"- {internal_id}")

        if args.with_steam_web:
            try:
                # Steam Web API 的参数用 publishedfileid（通常是数字）
                steam_dependents = get_dependent_mods_from_steam_web_api(target_item.published_id)
                print(f"\nSteam Web API dependents: {len(steam_dependents)} 个")
                for mod_id in steam_dependents[: args.limit]:
                    print(f"- https://steamcommunity.com/sharedfiles/filedetails/?id={mod_id}")
            except Exception as e:
                print(f"\nSteam Web API 查询失败：{e}")

        return 0

    if args.cmd == "cycles":
        items_by_internal_id, internal_id_by_published_id = scan_local_workshop(args.workshop_dir)
        root_item = resolve_mod_id(args.root, items_by_internal_id, internal_id_by_published_id)
        cycles = find_cycles_from_root(root_item.internal_id, items_by_internal_id)
        print(f"Cycles reachable from root: {len(cycles)}")
        for i, c in enumerate(cycles, start=1):
            # 格式化成 id->id->...->id
            pretty = []
            for internal_id in c:
                item = items_by_internal_id.get(internal_id)
                pretty.append(item.title if item else internal_id)
            print(f"{i}. " + " -> ".join(pretty))
        return 0

    parser.error("未知命令")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())