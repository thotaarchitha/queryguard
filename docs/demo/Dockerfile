# VHS plus a psql client, for recording docs/demo.gif against the compose stack.
FROM ghcr.io/charmbracelet/vhs:v0.8.0
# The image's apt sources say "stable", which Debian has since moved from bookworm to trixie.
# Pin them back so packages match the base image.
RUN for f in /etc/apt/sources.list /etc/apt/sources.list.d/*; do [ -f "$f" ] && sed -i 's/\bstable\b/bookworm/g' "$f"; done; \
    apt-get update && apt-get install -y --no-install-recommends postgresql-client && rm -rf /var/lib/apt/lists/*
