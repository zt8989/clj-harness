# [DEBUG-m1lp] snap JS heap and listeners after N flip rounds
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 40

SNAP = """() => {
  let nodes = 0, listeners = 0, detached = 0;
  const all = document.getElementsByTagName('*');
  for (const el of all) {
    nodes += 1;
    if (!el.isConnected) detached += 1;
    const keys = Object.keys(el);
    for (const k of keys) {
      const v = el[k];
      if (v && typeof v === 'object' && typeof v.getEventListeners === 'function') {
        listeners += Object.keys(v.getEventListeners()).length;
      }
    }
  }
  return { nodes, listeners, detached, jsHeap: performance.memory ? performance.memory.usedJSHeapSize : null };
}"""

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

    base = page.evaluate(SNAP)
    print("baseline:", base)

    for i in range(ROUNDS):
        page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
        page.wait_for_timeout(200)
        rows = page.locator("[data-slot='trajectory-item'] button")
        if rows.count() > 0:
            rows.first.click()
            page.wait_for_timeout(150)
        page.click("[data-slot='view-switch-tab'][data-view='conversation']")
        page.wait_for_timeout(200)

    # force GC via CDP (Memory domain)
    client = context.new_cdp_session(page)
    try:
        client.send("Memory.collectGarbage", {})
        client.send("Memory.forciblyPurgeJavaScriptMemory", {})
    except Exception as e:
        print("CDP GC unavailable:", e)
    after = page.evaluate(SNAP)
    print("after:", after)
    # second batch
    for i in range(ROUNDS):
        page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
        page.wait_for_timeout(150)
        page.click("[data-slot='view-switch-tab'][data-view='conversation']")
        page.wait_for_timeout(150)
    page.evaluate("() => { if (window.gc) window.gc(); }")
    after2 = page.evaluate(SNAP)
    print("after2 (another %d flips):" % ROUNDS, after2)
    browser.close()
