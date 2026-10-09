FROM node:22-alpine
WORKDIR /app/mcp-servers/jobs-mcp
# 依赖要装进镜像，否则容器里没有 jobhunt-cli / MCP SDK / express，服务起不来
COPY mcp-servers/jobs-mcp/package.json mcp-servers/jobs-mcp/package-lock.json ./
RUN npm ci --omit=dev
COPY mcp-servers/jobs-mcp/src ./src
COPY backend/offerpilot-job/src/main/resources/jobs/snapshot.json /app/backend/offerpilot-job/src/main/resources/jobs/snapshot.json
EXPOSE 7001
CMD ["node", "src/server.js"]
