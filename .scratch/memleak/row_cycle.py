# [DEBUG-m1lp] full cycle: bind a project via API, create sessions, then click rows A<->B N times.
# Covers: host switch (A<->B) + view flips inside each.
import sys, json, urllib.request
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
BACKEND = sys.argv[2] if len(sys.argv) > 2 else "11436"
SWITCHES = int(sys.argv[3]) if len(sys.argv) > 3 else 40

def api(path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{BACKEND}{path}", method=method, data=data,
                                 headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req) as r:
        b = r.read().decode()
        return json.loads(b) if b else None

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)

    # bind the page's minted session to the workspace project so the sidebar lists it
    first_id = page.evaluate("() => window.location.href")  # placeholder
    # grab the current thread id from the page? Instead: bind a fresh one we make ourselves.
    api("/api/project", "POST", {"threadId": "cyc-a", "dir": r"C:\Users\zhouteng\Documents\workspace\lisp-harness"})
    api("/api/project", "POST", {"threadId": "cyc-b", "dir": r"C:\Users\zhouteng\Documents\workspace\lisp-harness"})
    page.click("[data-slot='sidebar-refresh']")
    page.wait_for_timeout(800)

    rows = page.locator("[data-slot='thread-list-item-trigger']")
    print("rows:", rows.count())
    if rows.count() < 2:
        print(json.dumps(page.evaluate("() => document.querySelector('[data-slot=sidebar]')?.innerText.slice(0,500)")))
        sys.exit(1)

    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        d = page.evaluate("() => { let n=0; for (const el of document.getElementsByTagName('*')) if (!el.isConnected) n+=1; return n; }")
        print(tag, round(m/1e6,1), "MB detached:", d)
        return m

    # open session A by clicking its row (mounts a host + hydrates), then B
    rows.nth(0).click()
    page.wait_for_timeout(1500)
    rows = page.locator("[data-slot='thread-list-item-trigger']")
    rows.nth(1).click()
    page.wait_for_timeout(1500)
    snap("both-open")

    base = None
    for i in range(SWITCHES):
        rows = page.locator("[data-slot='thread-list-item-trigger']")
        n = rows.count()
        # the current session's row is disabled; click the other one
        clicked = False
        for j in range(n):
            btn = rows.nth(j)
            if not btn.is_disabled():
                btn.click(); clicked = True; break
        if not clicked:
            print("no enabled row to click")
            break
        page.wait_for_timeout(600)
        if i % 10 == 9:
            m = snap(f"switch-{i+1}")
            if base is None: base = m
    growth = (base or 0)
    snap("final")
    browser.close()
