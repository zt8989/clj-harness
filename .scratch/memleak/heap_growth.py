# [DEBUG-m1lp] heap growth over 60 flips without GC, sampling usedJSHeapSize
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 60

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)
    vis = page.locator("textarea:visible")
    vis.first.click()
    vis.first.fill("hello leak test")
    vis.first.press("Enter")
    page.wait_for_timeout(5000)

    samples = []
    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        samples.append((tag, m))

    snap("after-turn")
    for i in range(ROUNDS):
        page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
        page.wait_for_timeout(120)
        page.click("[data-slot='view-switch-tab'][data-view='conversation']")
        page.wait_for_timeout(120)
        if i % 10 == 9:
            snap(f"round-{i+1}")

    print(json.dumps(samples, indent=1))
    growth = samples[-1][1] - samples[0][1]
    print("heap growth MB:", round(growth / 1e6, 2))
    print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
