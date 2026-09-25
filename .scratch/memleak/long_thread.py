# [DEBUG-m1lp] high-turn stress: one session, 30 turns (60 model calls), flip views between each, then heavy flip stress.
# The conversation grows to 30 turns — long content — which multiplies what each flip re-renders.
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
BACKEND = sys.argv[2] if len(sys.argv) > 2 else "11436"
TURNS = int(sys.argv[3]) if len(sys.argv) > 3 else 30
FLIPS = int(sys.argv[4]) if len(sys.argv) > 4 else 60

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://[::1]:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)

    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        d = page.evaluate("() => { let n=0; for (const el of document.getElementsByTagName('*')) if (!el.isConnected) n+=1; return n; }")
        print(tag, round(m/1e6,1), "MB detached:", d)
        return m

    vis = page.locator("textarea:visible")
    for i in range(TURNS):
        vis.first.fill(f"turn number {i} of the stress run")
        vis.first.press("Enter")
        page.wait_for_timeout(900)   # scripted answers are instant; 900ms is plenty
    page.wait_for_timeout(2000)
    snap("after-30-turns")

    base = snap("pre-flip")
    samples = []
    for i in range(FLIPS):
        page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
        page.wait_for_timeout(120)
        page.click("[data-slot='view-switch-tab'][data-view='conversation']")
        page.wait_for_timeout(120)
        if i % 20 == 19:
            samples.append(snap(f"flip-{i+1}"))
    if len(samples) >= 2:
        growth = samples[-1] - samples[0]
        print("heap growth MB:", round(growth/1e6, 2))
        print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
