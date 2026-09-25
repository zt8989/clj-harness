# [DEBUG-m1lp] prod-build page recon
import threading, http.server, functools, socketserver
from playwright.sync_api import sync_playwright
UI_PORT=5299; DIST="ui/dist"
Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=DIST)
class Srv(socketserver.TCPServer): allow_reuse_address=True
srv=Srv(("127.0.0.1",UI_PORT),Handler)
threading.Thread(target=srv.serve_forever,daemon=True).start()
with sync_playwright() as p:
    b=p.chromium.launch(headless=True); page=b.new_page()
    errs=[]
    page.on("console", lambda m: errs.append((m.type,m.text[:200])))
    page.on("pageerror", lambda e: errs.append(("pageerror", str(e)[:300])))
    page.goto(f"http://127.0.0.1:{UI_PORT}/", wait_until="load", timeout=60000)
    page.wait_for_timeout(3000)
    print("URL:", page.url)
    print("slots:", page.evaluate("() => [...document.querySelectorAll('[data-slot]')].slice(0,25).map(e=>e.getAttribute('data-slot'))"))
    print("VITE_AGENT_URL baked at build time is what the prod page uses; check config.")
    for e in errs[:10]: print("CONSOLE:", e)
    b.close()
srv.shutdown()
