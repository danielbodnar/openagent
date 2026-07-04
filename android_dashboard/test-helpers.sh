#!/usr/bin/env bash
# Helpers for TEST_PLAN.md. Source after exporting:
#   APP_ID=sh.sandboxed.dashboard
#   RUN_ID=…
#   ARTIFACT_DIR=…
#   BACKEND_URL_HOST=…
#   BACKEND_URL_APP=…
#   BACKEND_TOKEN=…   (optional; required for API probes)

set -o pipefail

# --- dump helpers ---------------------------------------------------------

# Dump the current UI as a parsed label list. Saves both labels (default
# argument or arg 1) and the raw XML (.xml sibling) for diagnostics.
#
# Output is one label per line: "<text-or-content-desc>\t<resource-id>\t<bounds>".
dump_ui() {
  local labels_path="${1:-$ARTIFACT_DIR/ui-labels.txt}"
  local xml_path="${labels_path%.txt}.xml"
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 && break
    sleep 1
  done
  adb exec-out cat /sdcard/window.xml > "$xml_path"
  python3 - "$xml_path" <<'PY' > "$labels_path"
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception as e:
    print(f"DUMP_PARSE_ERR\t{e}")
    raise SystemExit
for n in root.iter("node"):
    label = n.attrib.get("text") or n.attrib.get("content-desc") or ""
    rid = n.attrib.get("resource-id", "")
    bounds = n.attrib.get("bounds", "")
    if label or rid:
        print(f"{label}\t{rid}\t{bounds}")
PY
  cat "$labels_path"
}

# Return center "x y" of the node with the given resource-id (testTag).
# Use as: read x y < <(find_tag terminal.send); adb shell input tap $x $y
find_tag() {
  local tag="$1"
  local xml_path="${ARTIFACT_DIR:-/tmp}/_last.xml"
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/window.xml > "$xml_path"
  python3 - "$xml_path" "$tag" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
needle = sys.argv[2]
for n in root.iter("node"):
    if n.attrib.get("resource-id") != needle:
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.attrib.get("bounds", ""))
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    raise SystemExit
raise SystemExit(2)
PY
}

# Tap the node whose testTag matches exactly. Prefer this over tap_label.
tap_tag() {
  local tag="$1"
  local xy
  xy=$(find_tag "$tag") || { echo "tag not found: $tag" >&2; return 1; }
  adb shell input tap $xy
}

# Type text into a tag-identified field. Spaces become %s for `adb input`.
# Avoid quotes, backslashes, $ — see TEST_PLAN.md "Text entry" notes.
# The field is cleared first via select-all + delete so pre-filled placeholders
# (e.g. "https://" in the auth URL field) don't get prepended to the new value.
type_into_tag() {
  local tag="$1"
  local text="$2"
  tap_tag "$tag" || return 1
  sleep 0.3
  # Move to end, then delete generously. KEYCODE_MOVE_END + bulk DEL is more
  # reliable than Ctrl+A across emulator builds.
  adb shell input keyevent KEYCODE_MOVE_END
  for _ in $(seq 1 80); do adb shell input keyevent KEYCODE_DEL; done
  sleep 0.2
  adb shell input text "$(printf '%s' "$text" | sed 's/ /%s/g')"
  sleep 0.2
  # Hide the soft keyboard so subsequent taps on toolbar/footer buttons hit.
  adb shell input keyevent KEYCODE_BACK
}

# Poll for a label or resource-id to appear. Returns 0 on hit, 1 on timeout.
#   wait_for tag terminal.send 5
#   wait_for label "Connected (single_tenant)" 8
wait_for() {
  local kind="$1"      # "tag" or "label"
  local needle="$2"
  local timeout_s="${3:-5}"
  local deadline=$(( SECONDS + timeout_s ))
  while [ $SECONDS -lt $deadline ]; do
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/window.xml > "${ARTIFACT_DIR:-/tmp}/_wait.xml" 2>/dev/null
    case "$kind" in
      tag)
        grep -q "resource-id=\"$needle\"" "${ARTIFACT_DIR:-/tmp}/_wait.xml" && return 0 ;;
      label)
        # Search text= and content-desc= attributes.
        grep -qE "text=\"[^\"]*$needle|content-desc=\"[^\"]*$needle" "${ARTIFACT_DIR:-/tmp}/_wait.xml" && return 0 ;;
    esac
    sleep 0.5
  done
  return 1
}

# --- artifact helpers -----------------------------------------------------

screenshot() {
  adb exec-out screencap -p > "$ARTIFACT_DIR/$1"
}

# Save a one-line case result to results.jsonl. Call this once per case.
# (`status` is read-only in zsh, so we use `result` here.)
record_result() {
  local case_id="$1"      # e.g. "06"
  local case_name="$2"
  local result="$3"       # pass | fail | blocked | n_a
  local classification="${4:-}"  # APP_FAIL | BACKEND_FAIL | CONTRACT_FAIL | ENV_BLOCKED | N/A
  local note="${5:-}"
  python3 - "$ARTIFACT_DIR/results.jsonl" "$case_id" "$case_name" "$result" "$classification" "$note" <<'PY'
import json, sys
path, case_id, name, result, classification, note = sys.argv[1:]
record = {
    "case": case_id,
    "name": name,
    "status": result,
    "classification": classification or None,
    "note": note or None,
}
with open(path, "a") as f:
    f.write(json.dumps(record) + "\n")
PY
}

# Auto-collect diagnostics on case failure. Install per-case:
#   CASE=06; trap 'on_case_fail "$CASE"' ERR
# Then `set -e` will trigger this on the first failing command.
on_case_fail() {
  local case_id="${1:-unknown}"
  adb exec-out screencap -p > "$ARTIFACT_DIR/$case_id-fail.png" 2>/dev/null || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/window.xml > "$ARTIFACT_DIR/$case_id-fail.xml" 2>/dev/null || true
  adb logcat -d -t 800 > "$ARTIFACT_DIR/$case_id-fail-logcat.txt" 2>/dev/null || true
  echo "case $case_id: diagnostics captured to $ARTIFACT_DIR/$case_id-fail.*" >&2
}

# Summarise results.jsonl as a markdown table on stdout.
summarise_results() {
  python3 - "$ARTIFACT_DIR/results.jsonl" <<'PY'
import json, sys, pathlib
path = pathlib.Path(sys.argv[1])
if not path.exists():
    print("(no results recorded)")
    raise SystemExit
rows = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
status_counts = {}
for r in rows:
    status_counts[r["status"]] = status_counts.get(r["status"], 0) + 1
print("| Case | Name | Status | Classification | Note |")
print("|---|---|---|---|---|")
for r in rows:
    print(f"| {r['case']} | {r['name']} | {r['status']} | {r.get('classification') or ''} | {(r.get('note') or '').replace('|','/')} |")
print()
print("Summary:", ", ".join(f"{k}={v}" for k, v in sorted(status_counts.items())))
PY
}
