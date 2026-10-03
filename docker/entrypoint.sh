#!/usr/bin/env bash
# 容器启动入口：写环境快照 -> 启动时触发一次导入 -> 启动 cron
#
# 原因：cron 守护进程不会继承 docker/compose 传入的环境变量，
# 因此定时任务需要先 source 这份快照才能拿到 NEO4J_AUTH 等。
set -eu

env_file=/app/cron.env
: > "$env_file"
printf '%s\n' '#!/usr/bin/env bash' >> "$env_file"

# printf %q 负责正确转义含空格/特殊字符的值（例如密码）
printenv | sort | while IFS='=' read -r key value; do
  [ -n "${key:-}" ] || continue
  printf 'export %s=%q\n' "$key" "$value" >> "$env_file"
done

chmod 600 "$env_file"

# 把导入日志转发到容器 stdout，方便 docker logs 查看
log_file=/var/log/workshop-import.log
touch "$log_file"
tail -n 0 -F "$log_file" &

echo "[entrypoint] cron env snapshot -> $env_file"
echo "[entrypoint] tailing $log_file"

# 启动时立即触发一次导入；设 RUN_ON_STARTUP=false 可关闭
# run-import.sh 内部有 flock，若此时 cron 触发已在跑则自动跳过
if [ "${RUN_ON_STARTUP:-true}" = "true" ]; then
  echo "[entrypoint] RUN_ON_STARTUP=true -> 立即触发一次导入（后台执行，不阻塞 cron）"
  /usr/local/bin/run-import.sh &
else
  echo "[entrypoint] RUN_ON_STARTUP=${RUN_ON_STARTUP} -> 跳过启动触发"
fi

echo "[entrypoint] starting: $*"
exec "$@"
