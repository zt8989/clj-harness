# [DEBUG-m1lp] language toggle stress via localStorage + reload, plus settings-open re-render cycles
import sys
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
CYCLES = int(sys.argv[2]) if len(sys.argv) > 2 else 20

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

    # run a turn so the conversation has content
    vis = page.locator("textarea:visible")
    vis.first.fill("hello leak")
    vis.first.press("Enter")
    page.wait_for_timeout(3000)
    snap("baseline")

    samples = []
    for i in range(CYCLES):
        # language re-render without reload, via the settings panel select
        page.click("[data-slot='sidebar-settings']")
        page.wait_for_timeout(700)
        sel = page.locator("[data-slot='settings-panel'] select").first
        opts = sel.evaluate("el => [...el.options].map(o => o.value)")
        val = sel.input_value()
        other = [o for o in opts if o != val]
        if other:
            sel.select_option(other[0])
        page.wait_for_timeout(400)
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)
        if i % 5 == 4:
            samples.append(snap(f"cycle-{i+1}"))

    if len(samples) >= 2:
        growth = samples[-1] - samples[0]
        print("heap growth MB:", round(growth/1e6, 2))
        print("VERDICT:", "RED (leak)" if growth > 10e6 else "GREEN (bounded)")
    browser.close()
