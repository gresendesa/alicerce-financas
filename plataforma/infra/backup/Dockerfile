# alicerce: curl fica na imagem para o ping do alerta externo.
FROM postgres:16.15-bookworm

ARG RESTIC_VERSION=0.18.1

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates curl bzip2 \
    && curl --fail --silent --show-error --location \
      "https://github.com/restic/restic/releases/download/v${RESTIC_VERSION}/restic_${RESTIC_VERSION}_linux_amd64.bz2" \
      | bunzip2 > /usr/local/bin/restic \
    && chmod 0755 /usr/local/bin/restic \
    && restic version \
    && apt-get purge --auto-remove -y bzip2 \
    && rm -rf /var/lib/apt/lists/*

COPY backup-once.sh backup-loop.sh list-versions.sh restore-latest.sh /usr/local/bin/
RUN chmod 0755 /usr/local/bin/backup-once.sh /usr/local/bin/backup-loop.sh /usr/local/bin/list-versions.sh /usr/local/bin/restore-latest.sh

USER postgres
ENTRYPOINT ["/usr/local/bin/backup-loop.sh"]
