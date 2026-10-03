# 定时导入容器：每天 06:00 (Asia/Shanghai) 执行 main.sh
#
# 说明：
# - 基于官方 Playwright 镜像，系统库（libnss3 等）和 Node 已就绪
# - Babashka 以静态二进制方式安装
# - @playwright/cli 固定为 0.1.22，并安装它自己要求的 chromium 版本
#   （基础镜像自带的 chromium 版本与 CLI 不一致，故不使用）
# - Neo4j 连接信息全部由环境变量注入，不打进镜像

FROM mcr.microsoft.com/playwright:v1.63.0-noble

ARG TARGETARCH=arm64
ARG BB_VERSION=1.13.225
ARG PW_CLI_VERSION=0.1.22

ENV TZ=Asia/Shanghai \
    DEBIAN_FRONTEND=noninteractive

# ── 时区 + cron ───────────────────────────────────────────────────────────────
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
 && echo $TZ > /etc/timezone \
 && apt-get update \
 && apt-get install -y --no-install-recommends cron tzdata util-linux ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# ── Babashka（静态二进制）─────────────────────────────────────────────────────
RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) BB_ARCH=amd64 ;; \
      arm64) BB_ARCH=aarch64 ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/bb.tar.gz \
      "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-${BB_ARCH}-static.tar.gz"; \
    tar -xzf /tmp/bb.tar.gz -C /usr/local/bin bb; \
    chmod +x /usr/local/bin/bb; \
    rm -f /tmp/bb.tar.gz; \
    bb --version

WORKDIR /app

# ── 应用代码 ─────────────────────────────────────────────────────────────────
COPY bb.edn ./
COPY src ./src
COPY *.bb.clj ./
COPY main.sh ./

# ── Playwright CLI（本地安装，保证 npx 离线可解析）+ 对应 chromium ────────────
RUN npm install --no-fund --no-audit "@playwright/cli@${PW_CLI_VERSION}" \
 && npx @playwright/cli install-browser chromium \
 && rm -rf /root/.npm

# playwright-cli 默认走 chrome 渠道（容器内没有 Chrome），强制使用 chromium
RUN mkdir -p /root/.playwright \
 && printf '%s\n' '{ "browser": { "browserName": "chromium" } }' > /root/.playwright/cli.config.json

# ── 定时任务 ─────────────────────────────────────────────────────────────────
# CRON_SCHEDULE 可在构建时覆盖；测试用 --build-arg CRON_SCHEDULE="* * * * *"
ARG CRON_SCHEDULE="0 6 * * *"

COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
COPY docker/run-import.sh /usr/local/bin/run-import.sh
COPY docker/crontab /tmp/crontab.template
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/run-import.sh \
 && sed "s|@SCHEDULE@|${CRON_SCHEDULE}|" /tmp/crontab.template > /etc/cron.d/workshop-import \
 && rm -f /tmp/crontab.template \
 && chmod 0644 /etc/cron.d/workshop-import \
 && touch /var/log/workshop-import.log

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["cron", "-f"]
