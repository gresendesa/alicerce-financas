#!/usr/bin/env bash
# Build e testes da plataforma dentro de um contêiner JDK 21; nada é instalado no host.
# Uso: ./ci.sh [tarefas do Gradle]   (padrão: build)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="eclipse-temurin:21.0.12.1_1-jdk"
# Daemon do root (o mesmo da produção). Sobrescreva com DOCKER=docker para outro daemon.
DOCKER="${DOCKER:-sudo docker}"
SOCK=/var/run/docker.sock

# Roda com o usuário do host, para os arquivos gerados não ficarem com dono root.
# --network host: as portas dos contêineres de teste ficam acessíveis em localhost.
exec $DOCKER run --rm --network host \
  -u "$(id -u):$(id -g)" --group-add "$(stat -c %g "$SOCK")" \
  -v "$PWD":/w -w /w \
  -e GRADLE_USER_HOME=/w/.gradle-home \
  -e HOME=/tmp \
  -v "$SOCK":/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  "$IMAGE" ./gradlew --no-daemon "${@:-build}"
