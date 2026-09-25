# [DEBUG-m1lp] stream + flip stress: run turn in A, A<->B row switches, view flips, DURING a live stream too.
# Red signal: JS heap growth or detached DOM growth across cycles.
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
BACKEND = sys.argv[2] if len(sys.argv) > 2 else "11436"
CYCLES = int(sys.argv[3]) if len(sys.argv) > 3 else 30

def api(path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{BACKEND}{path}", method=method, data=data,
                                 headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req) as r:
        b = r.read().decode()
        return json.loads(b) if b else None

import urllib.request
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://[::1]:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)

    def run_turn(text):
        vis = page.locator("textarea:visible")
        vis.first.click()
        vis.first.fill(text)
        vis.first.press("Enter")
        page.wait_for_timeout(3500)

    run_turn("hello A")
    page.click("[data-slot='sidebar-new-task']")
    page.wait_for_timeout(800)
    run_turn("hello B")

    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        d = page.evaluate("() => { let n=0; for (const el of document.getElementsByTagName('*')) if (!el.isConnected) n+=1; return n; }")
        print(tag, round(m/1e6,1), "MB detached:", d)
        return m

    # STREAMING FLIP: start a turn in the shown session, flip views while it streams
    vis = page.locator("textarea:visible")
    vis.first.fill("stream flip test")
    vis.first.press("Enter")
    page.wait_for_timeout(300)  # stream is live now
    for i in range(10):
        page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
        page.wait_for_timeout(150)
        page.click("[data-slot='view-switch-tab'][data-view='conversation']")
        page.wait_for_timeout(150)
    page.wait_for_timeout(4000)  # let the run settle
    snap("after-stream-flips")

    base = snap("pre-cycle")
    samples = []
    for i in range(CYCLES):
        rows = page.locator("[data-slot='thread-list-item-trigger']")
        n = rows.count()
        clicked = False
        for j in range(n):
            btn = rows.nth(j)
            if not btn.is_disabled():
                btn.click(); clicked = True; break
        if clicked:
            page.wait_for_timeout(500)
        # flip the view a few times
        page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
        page.wait_for_timeout(150)
        page.click("[data-slot='view-switch-tab'][data-view='conversation']")
        page.wait_for_timeout(150)
        if i % 10 == 9:
            samples.append(snap(f"cycle-{i+1}"))

    if len(samples) >= 2:
        growth = samples[-1] - samples[0]
        print("heap growth MB:", round(growth/1e6, 2))
        print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
