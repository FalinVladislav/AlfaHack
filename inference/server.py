"""Optional standalone inference API. No backend/database dependencies."""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen


def complete(instructions, message):
    model = os.environ.get("OLLAMA_MODEL", "")
    if not model:
        raise RuntimeError("OLLAMA_MODEL is not configured")
    payload = {
        "model": model,
        "stream": False,
        "format": "json",
        "options": {"temperature": 0},
        "messages": [
            {"role": "system", "content": instructions},
            {"role": "user", "content": message},
        ],
    }
    request = Request(
        os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/") + "/api/chat",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=int(os.environ.get("OLLAMA_TIMEOUT_SECONDS", "30"))) as response:
        result = json.load(response)
    content = json.loads(result["message"]["content"])
    if not isinstance(content, dict):
        raise ValueError("Model response must be an object")
    return content


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/health":
            return self.reply(404, {"code": "NOT_FOUND"})
        self.reply(200, {"status": "UP", "configured": bool(os.environ.get("OLLAMA_MODEL"))})

    def do_POST(self):
        if self.path != "/v1/chat":
            return self.reply(404, {"code": "NOT_FOUND"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 64000:
                return self.reply(413, {"code": "INVALID_SIZE"})
            body = json.loads(self.rfile.read(length))
            instructions, message = body["instructions"], body["message"]
            if not isinstance(instructions, str) or not isinstance(message, str):
                raise ValueError("Expected text")
        except (ValueError, KeyError, TypeError):
            return self.reply(400, {"code": "INVALID_REQUEST"})
        try:
            self.reply(200, complete(instructions, message))
        except Exception:
            self.reply(503, {"code": "MODEL_UNAVAILABLE"})


if __name__ == "__main__":
    ThreadingHTTPServer(
        (os.environ.get("INFERENCE_HOST", "127.0.0.1"), int(os.environ.get("INFERENCE_PORT", "8090"))),
        Handler,
    ).serve_forever()
