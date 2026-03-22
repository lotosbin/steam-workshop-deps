import requests

def get_dependent_mods(target_mod_id, app_id):
    """
    查询依赖目标 mod 的所有工坊项目
    :param target_mod_id: 目标 mod 的 ID（如 123456789）
    :param app_id: 游戏的 Steam App ID（如《僵尸毁灭工程》为 108600）
    :return: 依赖当前 mod 的 mod 列表
    """
    base_url = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
    params = {
        "key": "405D35561E82C568BE2D7A3042FC3C82",  # 从 Steam 开发者中心获取
        "itemcount": 1,
        "publishedfileids[0]": target_mod_id,
        "includemetadata": True
    }
    response = requests.get(base_url, params=params)
    data = response.json()
    dependent_mods = []
    if "publishedfiledetails" in data:
        for item in data["publishedfiledetails"]:
            if "dependents" in item:
                dependent_mods.extend(item["dependents"])
    return dependent_mods

# 示例：查询《僵尸毁灭工程》中 ID 为 123456789 的 mod 的依赖项
if __name__ == "__main__":
    target_mod_id = 2503622437
    app_id = 108600
    dependents = get_dependent_mods(target_mod_id, app_id)
    print(f"找到 {len(dependents)} 个依赖 mod：")
    for mod_id in dependents:
        print(f"https://steamcommunity.com/sharedfiles/filedetails/?id={mod_id}")