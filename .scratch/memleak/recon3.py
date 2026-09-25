# [DEBUG-m1lp] recon3: check the trajectory view actually rendered
import sys
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto(f"http://localhost:{UI}/", wait_until="networkidle", timeout=60000)
    page.wait_for_timeout(1000)
    vis = page.locator("textarea:visible")
    vis.first.click()
    vis.first.fill("hello leak test")
    vis.first.press("Enter")
    page.wait_for_timeout(5000)

    page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
    page.wait_for_timeout(2000)
    slots = page.evaluate("() => [...document.querySelectorAll('[data-slot]')].map(e => e.getAttribute('data-slot'))")
    print("trajectory slots:", [s for s in slots if 'trajectory' in s])
    print("text len:", page.evaluate("() => document.querySelector('[data-slot=trajectory-view]')?.innerText.slice(0,300)"))
    print("total nodes:", page.evaluate("() => document.getElementsByTagName('*').length"))

    page.click("[data-slot='view-switch-tab'][data-view='conversation']")
    page.wait_for_timeout(1000)
    print("back to conversation, total nodes:", page.evaluate("() => document.getElementsByTagName('*').length"))
    print("message slots:", page.evaluate("() => [...document.querySelectorAll('[data-slot]')].filter(s => s.getAttribute('data-slot').includes('message') || s.getAttribute('data-slot').includes('reasoning') || s.getAttribute('data-slot').includes('tool')).map(e => e.getAttribute('data-slot'))"))
    browser.close()
