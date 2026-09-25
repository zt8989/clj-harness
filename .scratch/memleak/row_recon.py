# [DEBUG-m1lp] row-click cycling with correct row discovery + open dialog checks
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

    # rows appear only when a project is bound; check for dialogs
    rows = page.locator("[data-slot='thread-list-item-trigger']")
    print("rows:", rows.count())
    dialogs = page.evaluate("() => [...document.querySelectorAll('[role=dialog]')].map(d => d.outerHTML.slice(0,200))")
    print("dialogs:", dialogs)
    # if a dialog is open, close it
    cancel = page.locator("[data-slot='sidebar-add-project-path-cancel']")
    if cancel.count() > 0 and cancel.first.is_visible():
        print("closing add-project dialog")
        cancel.first.click()
        page.wait_for_timeout(400)
    rows = page.locator("[data-slot='thread-list-item-trigger']")
    print("rows after close:", rows.count())
    browser.close()
