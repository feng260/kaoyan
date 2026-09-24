FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx prisma generate && npm run build

FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/prisma ./prisma
COPY --from=build /app/docs ./docs
# /admin 管理后台读取 public/admin.html,漏拷会导致该页面 404
COPY --from=build /app/public ./public
EXPOSE 3000
# 不加 --accept-data-loss:表结构变更需要删列时宁可启动失败,也不要静默清掉线上数据
CMD ["sh", "-c", "npx prisma db push --skip-generate && node dist/index.js"]