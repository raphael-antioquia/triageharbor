# Human-readable pin: node:24.20.0-alpine3.24
FROM node:24.20.0-alpine3.24@sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf AS builder

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# VITE_PROJECT_ID is public build-time configuration, not a secret.
# Never pass credentials, registry tokens, ingest keys, or other secrets
# through ARG; use an appropriate build-secret mechanism if needed later.
ARG VITE_PROJECT_ID

RUN test -n "$VITE_PROJECT_ID" \
    && npm run build \
    && find dist -type f -name '*.js' \
         -exec grep -lF "$VITE_PROJECT_ID" {} \; \
       | grep -q .

# Human-readable pin: caddy:2.11.4-alpine
FROM caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648 AS runtime

# Step 5A only packages the static frontend artifact.
# Step 5B will replace Caddy's default configuration with TriageHarbor's
# committed routing and authentication boundary.
COPY --from=builder /app/dist/ /usr/share/caddy/
