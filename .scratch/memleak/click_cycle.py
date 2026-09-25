# [DEBUG-m1lp] click-switch stress: run a turn in each of two sessions, then click rows A<->B many times.
# The switch path mounts nothing new (both hosts already live) but rerenders columns.
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
CYCLES = int(sys.argv[2]) if len(sys.argv) > 2 else 40

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
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

    rows = page.locator("[data-slot='thread-list-item-trigger']")
    print("rows:", rows.count())
    rowA = rows.nth(0)
    rowB = rows.nth(1)

    samples = []
    def snap(tag):
        m = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
        d = page.evaluate("""() => { let n=0; for (const el of document.getElementsByTagName('*')) if (!el.isConnected) n+=1; return n; }""")
        print(tag, round(m/1e6,1), "MB, detached:", d)
        samples.append(m)

    snap("baseline")
    for i in range(CYCLES):
        (rowA if i % 2 == 0 else rowB).click()
        page.wait_for_timeout(500)
        if i % 10 == 9:
            snap(f"cycle-{i+1}")

    growth = samples[-1] - samples[0]
    print("heap growth MB:", round(growth/1e6, 2))
    print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
