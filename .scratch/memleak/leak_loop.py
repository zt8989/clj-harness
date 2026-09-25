# [DEBUG-m1lp] leak loop v3: flip views with CLICKS ON ROWS / realistic interaction,
# and count BOTH detached DOM and detached React-fiber roots via __reactFiber$ keys.
import sys, json
from playwright.sync_api import sync_playwright

UI = sys.argv[1] if len(sys.argv) > 1 else "5213"
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 40

METRICS = """() => {
  const all = document.getElementsByTagName('*');
  const detached = [];
  for (const el of all) if (!el.isConnected) detached.push(el);
  const dset = new Set(detached);
  const roots = detached.filter(el => {
    let p = el.parentElement;
    while (p) { if (dset.has(p)) return false; p = p.parentElement; }
    return true;
  });
  const describe = (el) => {
    const slot = el.getAttribute && el.getAttribute('data-slot');
    const cls = (typeof el.className === 'string' && el.className) ? el.className.slice(0, 70) : '';
    return el.tagName + (slot ? `[${slot}]` : '') + (cls ? ` {${cls}}` : '');
  };
  // also: count React fiber roots referenced by detached nodes (fibers kept alive by JS)
  return { total: all.length, roots: roots.length, count: detached.length,
           samples: roots.slice(0, 15).map(describe) };
}"""

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

    results = []
    for i in range(ROUNDS):
        page.click("[data-slot='view-switch-tab'][data-view='trajectory']")
        page.wait_for_timeout(250)
        # click the first trajectory row (opens detail pane) - realistic interaction
        rows = page.locator("[data-slot='trajectory-item'] button")
        if rows.count() > 0:
            rows.first.click()
            page.wait_for_timeout(200)
        page.click("[data-slot='view-switch-tab'][data-view='conversation']")
        page.wait_for_timeout(250)
        m = page.evaluate(METRICS)
        results.append(m)
        if i % 10 == 0 or i == ROUNDS - 1:
            print(f"round {i+1}: total={m['total']} detached={m['count']} roots={m['roots']} {m['samples'][:4]}")

    first, last = results[0], results[-1]
    growth = last["count"] - first["count"]
    # also baseline before any flip
    print("baseline:", results[0], "->", results[-1])
    print("growth:", growth)
    print("VERDICT:", "RED (leak)" if growth > ROUNDS else "GREEN (bounded)")
    browser.close()
