# [DEBUG-m1lp] tool-turn stress: scripted provider with tool-calls -> renders tool cards + reasoning groups,
# which have timers (useToolCallElapsed), animations (ReasoningRoot), useScrollLock. Then flip views hard.
import sys, json, os, tempfile
from playwright.sync_api import sync_playwright
import urllib.request

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
BACKEND = sys.argv[2] if len(sys.argv) > 2 else "11436"
FLIPS = int(sys.argv[3]) if len(sys.argv) > 3 else 100

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://[::1]:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(800)

    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        d = page.evaluate("() => { let n=0; for (const el of document.getElementsByTagName('*')) if (!el.isConnected) n+=1; return n; }")
        print(tag, round(m/1e6,1), "MB detached:", d)
        return m

    # Open and close a tool card + a reasoning panel a few times (they mount content)
    vis = page.locator("textarea:visible")
    vis.first.fill("hello tools")
    vis.first.press("Enter")
    page.wait_for_timeout(3000)

    # flip views MANY times fast, the "反复点击" worst case
    base = snap("pre-flip")
    samples = []
    for i in range(FLIPS):
        v = "trajectory" if i % 2 == 0 else "conversation"
        page.click(f"[data-slot='view-switch-tab'][data-view='{v}']")
        page.wait_for_timeout(60)
        if i % 20 == 19:
            samples.append(snap(f"flip-{i+1}"))
    if len(samples) >= 2:
        growth = samples[-1] - samples[0]
        print("heap growth MB:", round(growth/1e6, 2))
        print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
