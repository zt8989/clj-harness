# [DEBUG-m1lp] settings open/close stress: the Dialog path — repeated open, probe, close.
# Red signal: JS heap grows per open/close cycle; detached DOM grows.
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
CYCLES = int(sys.argv[2]) if len(sys.argv) > 2 else 30

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)

    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        d = page.evaluate("() => { let n=0; for (const el of document.getElementsByTagName('*')) if (!el.isConnected) n+=1; return n; }")
        print(tag, round(m/1e6,1), "MB detached:", d)
        return m

    snap("baseline")
    samples = []
    for i in range(CYCLES):
        page.click("[data-slot='sidebar-settings']")
        page.wait_for_timeout(700)   # panel loads settings + registry + mcp
        # close via Escape
        page.keyboard.press("Escape")
        page.wait_for_timeout(500)
        if i % 10 == 9:
            samples.append(snap(f"cycle-{i+1}"))

    growth = samples[-1] - samples[0] if len(samples) >= 2 else 0
    print("heap growth MB:", round(growth/1e6, 2))
    print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
