# syntax=docker/dockerfile:1.7
# ----- deps -----
FROM node:25-alpine AS deps
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml* ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    (test -f pnpm-lock.yaml && pnpm install --frozen-lockfile) || pnpm install --no-frozen-lockfile

# ----- build -----
FROM node:25-alpine AS build
WORKDIR /app
RUN corepack enable
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

# ----- run -----
FROM node:25-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# Non-root user
RUN addgroup -g 1001 -S section && adduser -S section -u 1001 -G section

COPY --from=build --chown=section:section /app/public ./public
COPY --from=build --chown=section:section /app/.next/standalone ./
COPY --from=build --chown=section:section /app/.next/static ./.next/static

USER section
EXPOSE 3000
CMD ["node", "server.js"]
