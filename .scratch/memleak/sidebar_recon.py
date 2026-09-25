# [DEBUG-m1lp] sidebar recon: what does the sidebar hold after two sessions exist?
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
    vis.first.fill("hello A")
    vis.first.press("Enter")
    page.wait_for_timeout(3500)
    page.click("[data-slot='sidebar-new-task']")
    page.wait_for_timeout(800)
    vis = page.locator("textarea:visible")
    vis.first.click()
    vis.first.fill("hello B")
    vis.first.press("Enter")
    page.wait_for_timeout(3500)

    html = page.evaluate("() => document.querySelector('[data-slot=sidebar]')?.outerHTML ?? 'none'")
    print(html[:6000])
    browser.close()
