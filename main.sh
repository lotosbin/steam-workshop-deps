# 最近更新
bb steam_import_neo4j.bb.clj --appid 108600 --required-tag "Build 42" --sort lastupdated

# 最多订阅
bb steam_import_neo4j.bb.clj --appid 108600 --required-tag "Build 42" --sort totaluniquesubscribers

# 热门
bb steam_import_neo4j.bb.clj --appid 108600 --required-tag "Build 42" --sort trend

# 指定用户的 Workshop Items 页面
bb steam_import_neo4j.bb.clj \
  --user-workshop-url "https://steamcommunity.com/id/lotosbin/myworkshopfiles/?appid=108600" \
  --page 1 \
  --page-limit 1 \
  --max-depth 5 \
  --max-nodes 300

# 从指定合集详情页导入(单个 URL，包含 collection 及其条目依赖)
bb steam_import_single_neo4j.bb.clj --url "https://steamcommunity.com/sharedfiles/filedetails/?id=3624259825"

# 无聊的栀子 单人/多人联机通用模组合集
bb steam_import_single_neo4j.bb.clj --url "https://steamcommunity.com/sharedfiles/filedetails/?id=3623026433"

# 指定用户的 Collections 页面
bb steam_import_neo4j.bb.clj \
  --user-workshop-url "https://steamcommunity.com/id/lotosbin/myworkshopfiles/?section=collections&appid=108600" \
  --user-workshop-section collections \
  --page 1 \
  --page-limit 1 \
  --max-depth 5 \
  --max-nodes 300
