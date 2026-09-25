# [DEBUG-m1lp] session-switch stress: mint N fresh sessions via API, list+open each via UI sidebar, measure heap.
# This is the "反复点击" that cycles hosts (the only path that mounts/unmounts SessionHost).
import sys, json, time
import urllib.request
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
BACKEND = sys.argv[2] if len(sys.argv) > 2 else "11436"
CYCLES = int(sys.argv[3]) if len(sys.argv) > 3 else 25

def api(path, method="GET"):
    req = urllib.request.Request(f"http://127.0.0.1:{BACKEND}{path}", method=method)
    with urllib.request.urlopen(req) as r:
        body = r.read().decode()
        return json.loads(body) if body else None

# Build a few sessions by running a scripted turn on fresh threads via /api/agent (AG-UI).
# Simpler: just list threads; the page itself mints one. For cycling, use the sidebar.
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)
    vis = page.locator("textarea:visible")
    vis.first.click()
    vis.first.fill("hello A")
    vis.first.press("Enter")
    page.wait_for_timeout(4000)

    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        print(tag, round(m/1e6, 1), "MB")
        return m

    base = snap("after-turn-A")

    # create a second session via "new task" button, run a turn, then flip back and forth
    for cycle in range(CYCLES):
        page.click("[data-slot='sidebar-new-task']")
        page.wait_for_timeout(600)
        vis = page.locator("textarea:visible")
        vis.first.click()
        vis.first.fill(f"hello cycle {cycle}")
        vis.first.press("Enter")
        page.wait_for_timeout(3500)
        # now click the first session row in the sidebar to switch back
        rows = page.locator("[data-slot='sidebar-thread-row'], [data-slot^='sidebar'] button")
        # click by text 'hello A' if present
        try:
            page.get_by_text("hello A").first.click()
            page.wait_for_timeout(800)
        except Exception as e:
            print("switch back failed:", e)
        snap(f"cycle-{cycle+1}")

    growth = samples = 0
    browser.close()
