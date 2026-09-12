import http from 'node:http';

const port = Number(process.env.PORT || 7001);

const sites = [
  { id: 'tencent', name: '腾讯校招', status: 'stub' },
  { id: 'bytedance', name: '字节跳动', status: 'stub' },
  { id: 'meituan', name: '美团', status: 'stub' },
  { id: 'xiaomi', name: '小米', status: 'stub' },
];

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(json);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://localhost:${port}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    send(res, 200, { status: 'UP', tools: ['list_sites', 'search_jobs'] });
    return;
  }

  if (req.method === 'GET' && (url.pathname === '/list_sites' || url.pathname === '/mcp/list_sites')) {
    send(res, 200, { sites });
    return;
  }

  if (req.method === 'POST' && (url.pathname === '/search_jobs' || url.pathname === '/mcp/search_jobs')) {
    send(res, 200, {
      items: [],
      sources: [],
      note: 'M0 骨架：尚未接入 jobhunt-cli，将在本周完成站点验证后返回真实岗位。',
    });
    return;
  }

  send(res, 404, { message: 'not found' });
});

server.listen(port, () => {
  console.log(`jobs-mcp stub listening on ${port}`);
});
