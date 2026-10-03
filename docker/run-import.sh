#!/usr/bin/env bash
# 导入入口：加载环境变量快照 -> 加锁 -> 执行 main.sh -> 记录日志
#
# 既被 crontab 按点调度，也被 entrypoint.sh 在容器启动时立即调用一次；
# 两条路径共用同一把锁，避免重复跑长时间的抓取。
#
# 不使用 crontab 里的 `>> /proc/1/fd/1`：该重定向在 cron 子进程中行为不稳定，
# 一旦失败会导致整条命令（含 main.sh）都不执行。改为写日志文件，
# 由 entrypoint.sh 里的 tail -F 转发到容器 stdout。
set -uo pipefail

APP_DIR=/app
LOG_FILE=/var/log/workshop-import.log
ENV_SNAPSHOT=/app/cron.env
LOCK_FILE=/tmp/main.lock

touch "$LOG_FILE"

# 持有锁直到脚本退出；已有任务在跑则直接跳过本次触发
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "[$(date '+%F %T %Z')] SKIP   已有导入任务在运行，跳过本次触发" >> "$LOG_FILE"
  exit 0
fi

{
  echo "=============================================================="
  echo "[$(date '+%F %T %Z')] start  main.sh (pid $$)"
} >> "$LOG_FILE"

# cron 不继承容器的环境变量，这里加载 entrypoint 写下的快照
if [ -f "$ENV_SNAPSHOT" ]; then
  # shellcheck disable=SC1090
  . "$ENV_SNAPSHOT"
else
  echo "[$(date '+%F %T %Z')] WARN   $ENV_SNAPSHOT 不存在，改用当前环境" >> "$LOG_FILE"
fi

if ! cd "$APP_DIR"; then
  echo "[$(date '+%F %T %Z')] ERROR  无法进入 $APP_DIR" >> "$LOG_FILE"
  exit 1
fi

bash main.sh >> "$LOG_FILE" 2>&1
rc=$?

echo "[$(date '+%F %T %Z')] finish main.sh rc=$rc" >> "$LOG_FILE"
exit "$rc"
