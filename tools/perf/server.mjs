import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve, extname } from "node:path";
import { gzipSync } from "node:zlib";
import { fixture } from "./fixtures.mjs";

// Loopback only; mock responses remain outside all application bundles.
export function serve(dist, port = 4178) {
  const root = resolve(dist);
  const types = {
    ".html": "text/html",
    ".js": "text/javascript",
    ".css": "text/css",
    ".svg": "image/svg+xml",
    ".woff2": "font/woff2",
    ".json": "application/json",
  };
  const server = createServer(async (req, res) => {
    const path = new URL(req.url, "http://localhost").pathname;
    try {
      let body, type;
      if (path.startsWith("/api/")) {
        if (req.method !== "GET") throw new Error("Read-only fixture server");
        body = Buffer.from(
          JSON.stringify({
            code: 0,
            message: "ok",
            data: fixture(path),
            traceId: "perf-fixture",
          }),
        );
        type = "application/json";
        // Fixed latency includes a small data-loading interval in both phases.
        await new Promise((r) => setTimeout(r, 100));
        res.setHeader("Cache-Control", "no-store");
      } else {
        const file = resolve(root, `.${decodeURIComponent(path)}`);
        if (file !== root && !file.startsWith(root + "/"))
          throw new Error("Invalid path");
        try {
          body = await readFile(file);
          type = types[extname(file)];
        } catch {
          if (extname(path)) throw new Error("Missing asset");
          body = await readFile(resolve(root, "index.html"));
          type = "text/html";
        }
        res.setHeader(
          "Cache-Control",
          path.startsWith("/assets/")
            ? "public, max-age=31536000, immutable"
            : "no-cache",
        );
      }
      res.setHeader(
        "Content-Type",
        `${type || "application/octet-stream"}${type?.startsWith("text/") ? "; charset=utf-8" : ""}`,
      );
      if (/gzip/.test(req.headers["accept-encoding"] || "")) {
        body = gzipSync(body);
        res.setHeader("Content-Encoding", "gzip");
        res.setHeader("Vary", "Accept-Encoding");
      }
      res.setHeader("Content-Length", body.length);
      res.end(body);
    } catch (error) {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end(error.message);
    }
  });
  return new Promise((r) => server.listen(port, "127.0.0.1", () => r(server)));
}
