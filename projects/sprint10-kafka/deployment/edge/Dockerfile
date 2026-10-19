FROM caddy:2.10-alpine@sha256:4c6e91c6ed0e2fa03efd5b44747b625fec79bc9cd06ac5235a779726618e530d
# Порт выше 1024: file capability из upstream мешает запуску с cap_drop: ALL.
RUN setcap -r /usr/bin/caddy
