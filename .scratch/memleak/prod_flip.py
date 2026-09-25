# [DEBUG-m1lp] prod-build leak loop: same-origin page (harness serves ui/dist itself), scripted backend.
# The harness mounts the built UI (see harness.edge.http / resources path) — start a second scripted
# backend WITHOUT --script-file and point this loop at its UI address. Simpler: reuse the dev backend's
# harness UI: the e2e server serves the built UI at its own origin. We just don't know the port's UI
# path; instead we proxy /api to 11436 with a tiny threading proxy.
import sys, threading, http.server, socketserver, urllib.request, urllib.error, functools, json
from playwright.sync_api import sync_playwright

UI_PORT = 5299
BACKEND = "http://127.0.0.1:11436"
DIST = "ui/dist"
FLIPS = int(sys.argv[1]) if len(sys.argv) > 1 else 100

class _DeadProxy:  # [DEBUG-m1lp] unused leftover
    pass

class Mixed(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/api/"):
            _proxy_all(self, "GET")
        else:
            super().do_GET()
    def do_POST(self):
        if self.path.startswith("/api/"):
            _proxy_all(self, "POST")
        else:
            super().do_POST()

def _proxy_all(handler, method):
    length = int(handler.headers.get("Content-Length") or 0)
    body = handler.rfile.read(length) if length else None
    url = BACKEND + handler.path
    req = urllib.request.Request(url, data=body, method=method)
    for h in ("Content-Type",):
        if handler.headers.get(h): req.add_header(h, handler.headers[h])
    try:
        with urllib.request.urlopen(req) as r:
            data = r.read()
            handler.send_response(r.status)
            handler.send_header("Content-Type", r.headers.get("Content-Type","application/octet-stream"))
            handler.send_header("Content-Length", str(len(data)))
            handler.end_headers()
            handler.wfile.write(data)
    except urllib.error.HTTPError as e:
        data = e.read()
        handler.send_response(e.code)
        handler.send_header("Content-Type", e.headers.get("Content-Type","application/octet-stream"))
        handler.send_header("Content-Length", str(len(data)))
        handler.end_headers()
        handler.wfile.write(data)
    except Exception:
        handler.send_response(502)
        handler.end_headers()

class Srv(socketserver.ThreadingTCPServer):
    allow_reuse_address = True

srv = Srv(("127.0.0.1", UI_PORT), functools.partial(Mixed, directory=DIST))
threading.Thread(target=srv.serve_forever, daemon=True).start()
print("serving prod UI + /api proxy on", UI_PORT)

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://127.0.0.1:{UI_PORT}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)

    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        d = page.evaluate("() => { let n=0; for (const el of document.getElementsByTagName('*')) if (!el.isConnected) n+=1; return n; }")
        print(tag, round(m/1e6,1), "MB detached:", d)
        return m

    vis = page.locator("textarea:visible")
    vis.first.fill("hello prod")
    vis.first.press("Enter")
    page.wait_for_timeout(3000)

    base = snap("pre-flip")
    samples = []
    for i in range(FLIPS):
        v = "trajectory" if i % 2 == 0 else "conversation"
        page.click(f"[data-slot='view-switch-tab'][data-view='{v}']")
        page.wait_for_timeout(50)
        if i % 20 == 19:
            samples.append(snap(f"flip-{i+1}"))
    if len(samples) >= 2:
        growth = samples[-1] - samples[0]
        print("heap growth MB:", round(growth/1e6, 2))
        print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
srv.shutdown()
