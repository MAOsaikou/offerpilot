#!/usr/bin/env python3
"""Standard-library SSE MCP smoke: MCP_SERVER_URL + MCP_SERVER_API_KEY from env."""
import json
import os
import queue
import socket
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request


class SseMcpClient:
    def __init__(self, url, key):
        self.url, self.key = url, key
        self.events, self.sequence = queue.Queue(), 0
        request = urllib.request.Request(url, headers={"Accept": "text/event-stream", "X-API-Key": key})
        self.response = urllib.request.urlopen(request, timeout=90)
        threading.Thread(target=self._read, daemon=True).start()
        event, endpoint = self._next()
        if event != "endpoint":
            raise RuntimeError("Expected SSE endpoint event; check MCP_SERVER_URL ends in /sse")
        self.endpoint = urllib.parse.urljoin(url, endpoint)
        if urllib.parse.urlsplit(self.endpoint)[:2] != urllib.parse.urlsplit(url)[:2]:
            raise RuntimeError("Server advertised a message endpoint on another origin")
        self.call("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                 "clientInfo": {"name": "offerpilot-smoke", "version": "1"}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def _read(self):
        event, data = "message", []
        try:
            # HTTPResponse iterates complete lines, so UTF-8 codepoints cannot be split across decodes.
            for raw in self.response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line:
                    if data:
                        self.events.put((event, "\n".join(data)))
                    event, data = "message", []
                elif line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].removeprefix(" "))
            self.events.put(RuntimeError("SSE connection ended before response"))
        except Exception as error:
            self.events.put(error)

    def _next(self):
        try:
            value = self.events.get(timeout=90)
        except queue.Empty:
            raise RuntimeError("MCP response timed out; check server and upstream jobs service") from None
        if isinstance(value, Exception):
            raise value
        return value

    def _post(self, message):
        request = urllib.request.Request(self.endpoint, data=json.dumps(message, ensure_ascii=False).encode("utf-8"),
                                         headers={"Content-Type": "application/json", "X-API-Key": self.key}, method="POST")
        with urllib.request.urlopen(request, timeout=20) as response:
            response.read()

    def call(self, method, params):
        self.sequence += 1
        self._post({"jsonrpc": "2.0", "id": self.sequence, "method": method, "params": params})
        while True:
            _, data = self._next()
            reply = json.loads(data)
            if reply.get("id") == self.sequence:
                if "error" in reply:
                    raise RuntimeError("MCP protocol error: " + str(reply["error"].get("message")))
                return reply["result"]

    def close(self):
        # Interrupt the reader before closing its buffered file; close alone can wait for SSE EOF.
        try:
            connection = socket.socket(fileno=self.response.fileno())
            try:
                connection.shutdown(socket.SHUT_RDWR)
            finally:
                connection.detach()  # HTTPResponse still owns the descriptor.
        except (OSError, ValueError):
            pass
        self.response.close()


def main():
    url, key = os.environ.get("MCP_SERVER_URL", ""), os.environ.get("MCP_SERVER_API_KEY", "")
    if not url or not key.strip():
        raise RuntimeError("Set MCP_SERVER_URL (e.g. http://127.0.0.1:18081/sse) and MCP_SERVER_API_KEY")
    client = SseMcpClient(url, key)
    try:
        print("tools:", ", ".join(tool["name"] for tool in client.call("tools/list", {}).get("tools", [])))
        result = client.call("tools/call", {"name": "jobs.search", "arguments": {
            "query": sys.argv[1] if len(sys.argv) > 1 else "Java", "nature": "campus", "limit": 3}})
        if result.get("isError"):
            raise RuntimeError("jobs.search failed: " + str(result.get("content")))
        for content in result.get("content", []):
            if content.get("type") == "text":
                print(json.dumps(json.loads(content["text"]), ensure_ascii=False, indent=2))
    finally:
        client.close()


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        hint = "check X-API-Key" if error.code == 401 else "check server transport URL and logs"
        sys.exit(f"MCP HTTP {error.code}: {hint}")
    except Exception as error:
        sys.exit(f"MCP smoke failed: {error}")
