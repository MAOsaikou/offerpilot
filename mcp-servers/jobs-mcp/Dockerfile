FROM node:22-alpine
WORKDIR /app
# 依赖要装进镜像，否则容器里没有 jobhunt-cli / MCP SDK / express，服务起不来
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
EXPOSE 7001
CMD ["node", "src/server.js"]
