# CLI и buildx Docker 28 копируются из официального образа, закреплённого digest мультиархитектурного индекса.
# Docker 28 сохраняет минимальный API, с которым договаривается клиент Testcontainers проекта.
FROM docker:28-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d AS docker-cli

# Официальный inbound-агент Jenkins (remoting 3391, JDK 21), закреплён digest мультиархитектурного индекса.
FROM jenkins/inbound-agent:3391.va_37fa_a_305d6d-5-jdk21@sha256:64a27e807d216d527a7f5224e6ff9733b5cbd8650db3b5dfd0aea3b94949460f

USER root

# libnss-myhostname разрешает *.localhost в loopback и не трогает /etc/hosts, который Docker переписывает для каждого
# контейнера. Агент делит сетевой namespace с демоном, поэтому loopback — это порты kind и port-forward в нём же.
# Проверка валит сборку образа, а не pipeline, если имена банка (UI, авторизация, Grafana, Kibana) перестают разрешаться.
RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends --yes libnss-myhostname openssl procps python3 python3-venv xz-utils; \
    rm -rf /var/lib/apt/lists/*; \
    sed -i -E 's/^(hosts:[[:space:]]+)files(.*)$/\1files myhostname\2/' /etc/nsswitch.conf; \
    for host in bank auth-bank insights grafana-bank kibana-bank; do \
      test "$(getent ahostsv4 "$host.valgrind-home.localhost" | awk 'NR == 1 { print $1 }')" = 127.0.0.1; \
    done; \
    git config --system --add safe.directory /srv/bank11-source.git

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx

# Версии инструментов совпадают с .github/scripts/setup-kubernetes.sh и workflow Sprint 10/11. Каждый архив сверяется
# с опубликованным вендором SHA-256, записанным здесь, поэтому подменённая загрузка валит сборку образа.
# promtool и amtool сюда не входят: pipeline ставит их проверенным общим скриптом .github/scripts/setup-observability.sh.
RUN set -eux; \
    case "$(dpkg --print-architecture)" in \
      amd64) node_arch=x64; \
        node_sha=af59f88ed35c68f7196dc94938e74327e3abe62055b831746de5b23bd7e1670a; \
        tofu_sha=02800fafa2753a9f50c38483e2fdf5bc353fd62895eb9e25eec9a5145df3a69e; \
        helm_sha=0403a88ae20913d11b10a2467b50087721fea7581736da6b9d3992f5357fe54b; \
        kind_sha=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d; \
        kubectl_sha=8efbb9435132a190920eb65a47a8c1ecf755ad85ab57a600c9bedbab460bb7a8 ;; \
      arm64) node_arch=arm64; \
        node_sha=48ecb7ee501f69e2a27d9b4b724c05e82bbd1cf03df7152ce37628956b8303a7; \
        tofu_sha=d4f2ab15776925864b049bb329d69682851de6f5204f256e9fa86d07a0308850; \
        helm_sha=8cf8f66ecbc252ac8f7f06cca68aba88508ddfde3a6a34feb1678ad1694b4f88; \
        kind_sha=20022bee6cfcd5086cb7234d218e3454e6090022f2a8f55d1fa7fcf42c3867a2; \
        kubectl_sha=5b045a4712674c88a56fd98eef4285689738b7fbe8735e1b9ee3509521af5cb4 ;; \
      *) echo "Unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    arch="$(dpkg --print-architecture)"; \
    work="$(mktemp -d)"; cd "$work"; \
    fetch() { curl --fail --silent --show-error --location --retry 3 --output "$1" "$2"; printf '%s  %s\n' "$3" "$1" | sha256sum --check --strict; }; \
    fetch node.tar.xz "https://nodejs.org/dist/v24.4.0/node-v24.4.0-linux-${node_arch}.tar.xz" "$node_sha"; \
    tar -xJf node.tar.xz --strip-components=1 -C /usr/local; \
    fetch tofu.tar.gz "https://github.com/opentofu/opentofu/releases/download/v1.11.6/tofu_1.11.6_linux_${arch}.tar.gz" "$tofu_sha"; \
    tar -xzf tofu.tar.gz -C /usr/local/bin tofu; \
    fetch helm.tar.gz "https://get.helm.sh/helm-v4.3.1-linux-${arch}.tar.gz" "$helm_sha"; \
    tar -xzf helm.tar.gz --strip-components=1 -C /usr/local/bin "linux-${arch}/helm"; \
    fetch /usr/local/bin/kind "https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-${arch}" "$kind_sha"; \
    fetch /usr/local/bin/kubectl "https://dl.k8s.io/release/v1.34.11/bin/linux/${arch}/kubectl" "$kubectl_sha"; \
    chmod 0755 /usr/local/bin/kind /usr/local/bin/kubectl; \
    cd /; rm -rf "$work"; \
    node --version; tofu version; helm version --short; kind version; kubectl version --client; \
    javac -version; docker --version; docker buildx version

COPY entrypoint.sh /usr/local/bin/bank-agent-entrypoint
RUN chmod 0755 /usr/local/bin/bank-agent-entrypoint

# Один общий том хранит рабочие каталоги, состояние развёртывания и кэши. Контейнер Docker-демона монтирует тот же
# том по тому же пути, потому что источники bind mount у kind разрешаются на стороне демона.
# CHECKPOINT_DISABLE не даёт OpenTofu обращаться к сервису проверки версий во время plan и apply.
ENV GRADLE_USER_HOME=/home/jenkins/agent/.gradle \
    npm_config_cache=/home/jenkins/agent/.npm \
    CHECKPOINT_DISABLE=1
USER jenkins
ENTRYPOINT ["/usr/local/bin/bank-agent-entrypoint"]
