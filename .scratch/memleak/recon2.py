# [DEBUG-m1lp] recon2: find the composer input and send a turn
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.on("console", lambda m: print("CONSOLE:", m.type, m.text[:200]))
    page.on("response", lambda r: print("RESP:", r.status, r.url[-80:]) if "/api/" in r.url and r.request.method != "GET" else None)
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1500)

    tas = page.locator("textarea")
    for i in range(tas.count()):
        ta = tas.nth(i)
        vis = ta.is_visible()
        print(f"textarea {i}: visible={vis} placeholder={ta.get_attribute('placeholder')!r}")
    # try typing into the visible one
    vis = page.locator("textarea:visible")
    print("visible textareas:", vis.count())
    if vis.count() > 0:
        vis.first.click()
        vis.first.fill("hello leak test")
        page.wait_for_timeout(200)
        print("value after fill:", vis.first.input_value())
        vis.first.press("Enter")
        page.wait_for_timeout(5000)
        print("slots now:", page.evaluate("() => [...document.querySelectorAll('[data-slot]')].map(e => e.getAttribute('data-slot')).join(', ')"))
    browser.close()
