# [DEBUG-m1lp] recon: dump what's on the page
import sys
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1500)
    print("--- buttons ---")
    for b in page.locator("button").all()[:30]:
        print(repr(b.evaluate("el => el.outerHTML.slice(0,140)")))
    print("--- data-slots ---")
    print(page.evaluate("() => [...document.querySelectorAll('[data-slot]')].map(e => e.getAttribute('data-slot')).join(', ')"))
    print("--- textareas ---", page.locator("textarea").count())
    print("--- contenteditable ---", page.locator("[contenteditable]").count())
    browser.close()
