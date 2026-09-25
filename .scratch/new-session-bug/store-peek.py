import os, shutil, sqlite3, sys, time

home = os.path.expanduser("~/.clj-harness")
db = os.path.join(home, "harness.db")
tmp = os.path.join(os.environ.get("TEMP", "/tmp"), "probe-copy.db")
for suffix in ("", "-wal", "-shm"):
    src = db + suffix
    if os.path.exists(src):
        shutil.copyfile(src, tmp + suffix)
con = sqlite3.connect(tmp)
con.row_factory = sqlite3.Row
rows = con.execute("""
  SELECT s.id, s.title, s.last_sent_at, s.created_at, s.project_id, s.path, s.last_project_path,
         p.canonical_path AS project
    FROM sessions s LEFT JOIN projects p ON p.id = s.project_id
   ORDER BY s.created_at DESC LIMIT 30
""").fetchall()
print(f"{len(rows)} most recent sessions in {db}")
now = time.time() * 1000
for r in rows:
    age = (now - r["created_at"]) / 60000 if r["created_at"] else None
    print(f"  id={r['id']}")
    print(f"     title={r['title']!r} last_sent_at={r['last_sent_at']} created={r['created_at']} ({age:.0f} min ago)" if age is not None else "")
    print(f"     project_id={r['project_id']} path={r['path']!r} project={r['project']!r} last_project_path={r['last_project_path']!r}")
n_null = con.execute("SELECT count(*) FROM sessions WHERE title IS NULL").fetchone()[0]
n_null_send = con.execute("SELECT count(*) FROM sessions WHERE last_sent_at IS NULL").fetchone()[0]
total = con.execute("SELECT count(*) FROM sessions").fetchone()[0]
print(f"\n{total} sessions; {n_null} with a NULL title; {n_null_send} with a NULL last_sent_at")
named = con.execute("SELECT id FROM sessions WHERE id IN ('main','default','new')").fetchall()
print("sessions with a default-looking id:", [r[0] for r in named])