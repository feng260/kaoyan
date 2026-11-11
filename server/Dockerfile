FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx prisma generate && npm run build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=build --chown=node:node /app/prisma ./prisma
COPY --from=build --chown=node:node /app/docs ./docs
# /admin 管理后台读取 public/admin.html,漏拷会导致该页面 404
COPY --from=build --chown=node:node /app/public ./public
RUN mkdir -p /app/releases && chown -R node:node /app/releases
USER node
EXPOSE 3000
# 不加 --accept-data-loss:表结构变更需要删列时宁可启动失败,也不要静默清掉线上数据
CMD ["sh", "-c", "npx prisma db push --skip-generate && node dist/index.js"]
