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
COPY --from=build /app/node_modules/swagger-ui-dist ./node_modules/swagger-ui-dist
EXPOSE 3000
CMD npx prisma db push --accept-data-loss && node dist/index.js
