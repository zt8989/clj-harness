import sys, json

path = sys.argv[1]
msg_reason_chars = 0
msg_reason_bytes = 0
rows = 0
with_rc = 0
delta_chars = 0
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        p = row.get("payload")
        if row.get("type") == "message" and isinstance(p, dict) and p.get("role") == "assistant":
            rows += 1
            rc = p.get("reasoning_content")
            if isinstance(rc, str) and rc:
                with_rc += 1
                msg_reason_chars += len(rc)
                msg_reason_bytes += len(rc.encode("utf-8"))
        elif isinstance(p, dict) and p.get("type") == "REASONING_MESSAGE_CONTENT":
            delta_chars += len(p.get("delta") or "")

print("assistant message rows            ", rows)
print("   of them carrying reasoning_content", with_rc)
print("reasoning_content chars (message rows)", msg_reason_chars,
      "bytes", msg_reason_bytes, f"({msg_reason_bytes/1048576:.2f} MB)")
print("reasoning delta chars (frames)    ", delta_chars, f"({delta_chars/1048576:.2f} MB as utf-8 chars)")
print("frames that carry it              ", 222996, "lines, 42.66 MB")
