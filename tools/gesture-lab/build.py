import difflib
import html
import json
import re
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SRC_REL = "app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt"
SRC = ROOT / SRC_REL
TEMPLATE = HERE / "template.html"
OUT = HERE / "gesture-lab.html"
SHA = subprocess.check_output(["git", "-C", str(ROOT), "log", "-1", "--format=%H", "--", SRC_REL], text=True).strip()
LINES = open(SRC).read().split("\n")
GH = f"https://github.com/wassupluke/launcher/blob/{SHA}/{SRC_REL}"

IDENT = [("ANGULAR_THRESHOLD_NO_DIAGONALS", "angOff"), ("ANGULAR_THRESHOLD", "angOn"),
         ("TOUCH_SLOP_SQUARE", "slop"), ("TOUCH_SLOP", "slop"), ("MIN_TRIANGLE_HEIGHT", "tri"),
         ("systemGestureInsetTop", "insetTop"), ("systemGestureInsetBottom", "insetBottom"),
         ("diagonalSwipe()", "diag")]
IDENT_RE = re.compile("|".join(re.escape(i) for i, _ in IDENT))
IDENT_KEY = dict(IDENT)


def inp(k):
    return f'<input class="pv p-{k}" data-k="{k}" type="number" step="any" aria-label="{k}">'


def chk(k):
    return f'<label class="chip p-{k}"><input class="pv" data-k="{k}" type="checkbox"> enabled</label>'


def chip(label, k):
    return f'<span class="chip p-{k}">{label} = {inp(k)}</span>'


SUBS = {27: [("Math.PI / 8", "Math.PI / " + inp("angOn"))],
        28: [("Math.PI / 6", "Math.PI / " + inp("angOff"))],
        37: [("= 250", "= " + inp("tri"))]}
CHIPS = {138: chip("runtime scaledTouchSlop", "slop"),
         224: '<label class="chip p-diag">runtime diagonalSwipe() <input class="pv" data-k="diag" type="checkbox"></label>',
         351: chip("runtime insets.top", "insetTop"),
         352: chip("runtime insets.bottom", "insetBottom"),
         345: chip("runtime widthPixels", "W"),
         346: chip("runtime heightPixels", "H")}


def render_line(n):
    s = html.escape(LINES[n - 1])
    s = IDENT_RE.sub(lambda m: f'<span class="id p-{IDENT_KEY[m.group(0)]}">{m.group(0)}</span>', s)
    for a, b in SUBS.get(n, []):
        s = s.replace(a, b, 1)
    if n in CHIPS:
        s += "  " + CHIPS[n]
    old = " old" if 303 <= n <= 314 else ""
    return f'<span class="L{old}"><span class="ln">{n}</span>{s}</span>'


PROPOSED = """private val MIN_TRIANGLE_WIDTH = @triW@

private fun PointerPath.getTriangleGesture(): Gesture? {
    val start = getStart(); val end = getEnd(); val pathMin = getMin(); val pathMax = getMax()
    val seMin = start.minimum(end); val seMax = start.maximum(end)
    val dx = end.x - start.x; val dy = end.y - start.y
    val down = pathMax.y - seMax.y; val up = seMin.y - pathMin.y
    val right = pathMax.x - seMax.x; val left = seMin.x - pathMin.x
    val vertical = max(down, up); val horizontal = max(right, left)
    if (max(vertical, horizontal) <= MIN_TRIANGLE_HEIGHT) return null
    return if (vertical >= horizontal) {
        if (abs(dx) < MIN_TRIANGLE_WIDTH) null
        else if (down >= up) { if (dx > 0) Gesture.SWIPE_V else Gesture.SWIPE_V_REVERSE }
        else { if (dx > 0) Gesture.SWIPE_LAMBDA else Gesture.SWIPE_LAMBDA_REVERSE }
    } else {
        if (abs(dy) < MIN_TRIANGLE_WIDTH) null
        else if (left >= right) { if (dy > 0) Gesture.SWIPE_SMALLER else Gesture.SWIPE_SMALLER_REVERSE }
        else { if (dy > 0) Gesture.SWIPE_LARGER else Gesture.SWIPE_LARGER_REVERSE }
    }
}

val triangle = if (pointerCount == 1) mainPointerPath.getTriangleGesture() else null
var gesture = getGestureForDirection(mainPointerPath.getDirection()) ?: triangle ?: return
if (pointerCount > 1 && idToPath.values.any { getGestureForDirection(it.getDirection()) != gesture }) {
    return
}

val beforeTriangleVariants = gesture

if (gesture == beforeTriangleVariants && triangle != null) {
    gesture = triangle
}"""


def patched_source():
    parts = PROPOSED.split("\n")
    decl, fn, classify_head, before, after = parts[0], parts[2:20], parts[21:26], parts[27], parts[29:32]
    ind = lambda lines, n: [(" " * n + l) if l else l for l in lines]
    out = []
    for n, line in enumerate(LINES, 1):
        if n == 283:
            out.extend(ind(classify_head[:2], 8))
            continue
        if n == 286:
            out.append(line.replace("if (idToPath", "if (pointerCount > 1 && idToPath"))
            continue
        if n == 303:
            out.extend(ind([before, ""], 8))
        out.append(line)
        if n == 37:
            out.append(ind([decl], 4)[0])
        if n == 135:
            out.extend(ind(fn, 4) + [""])
        if n == 315:
            out.extend([""] + ind(after, 8))
    return out


def proposed_diff():
    with tempfile.TemporaryDirectory() as d:
        a, b = Path(d, "a.kt"), Path(d, "b.kt")
        a.write_text("\n".join(LINES))
        b.write_text("\n".join(patched_source()))
        res = subprocess.run(["git", "diff", "--no-index", "--no-color", "-U3", str(a), str(b)],
                             capture_output=True, text=True)
    rows, o = [], 0
    for line in res.stdout.split("\n"):
        if line.startswith("@@"):
            o = int(re.match(r"@@ -(\d+)", line).group(1))
            rows.append({"t": "@", "s": line.split(" @@")[0] + " @@"})
        elif not rows or line.startswith("\\"):
            continue
        elif line.startswith("+"):
            rows.append({"t": "+", "s": line[1:]})
        elif line.startswith("-") or line.startswith(" "):
            rows.append({"t": line[0], "s": line[1:], "o": o})
            o += 1
    return rows


def proposed_block():
    out = []
    for i, line in enumerate(PROPOSED.split("\n"), 1):
        s = html.escape(line)
        s = re.sub(r"\bMIN_TRIANGLE_HEIGHT\b", '<span class="id p-tri">MIN_TRIANGLE_HEIGHT</span>', s)
        s = re.sub(r"\bMIN_TRIANGLE_WIDTH\b", '<span class="id p-triW">MIN_TRIANGLE_WIDTH</span>', s)
        s = s.replace("@triW@", inp("triW"))
        out.append(f'<span class="L"><span class="ln">+{i}</span>{s}</span>')
    head = ('<div class="ref"><span>proposed</span>shape-first triangle rule '
            f'(lines +22 to +26 replace <a href="{GH}#L283-L288" target="_blank">:283-288</a>; '
            f'+28 goes before <a href="{GH}#L303-L314" target="_blank">:303-314</a>, +30 to +32 after it)</div>')
    return '<div class="proposed">' + head + '<pre class="code">' + "\n".join(out) + "</pre></div>"


def block(ranges):
    refs, out, prev = [], [], None
    for a, b in ranges:
        if prev is not None and a > prev + 1:
            out.append('<span class="ln">⋮</span>')
        out.extend(render_line(n) for n in range(a, b + 1))
        prev = b
        tag = f"{a}" if a == b else f"{a}-{b}"
        anchor = f"#L{a}" if a == b else f"#L{a}-L{b}"
        refs.append(f'<a href="{GH}{anchor}" target="_blank">:{tag}</a>')
    head = f'<div class="ref"><span>{SRC_REL.split("/")[-1]}</span>{" ".join(refs)}</div>'
    return head + '<pre class="code">' + "\n".join(out) + "</pre>"


BLOCKS = {
    "CODE_MAP": [(27, 29), (37, 37), (113, 118), (138, 138), (220, 250), (297, 314), (345, 346), (351, 352)],
    "CODE_ROSE": [(27, 28), (220, 243)],
    "CODE_V": [(27, 28), (37, 37), (224, 235), (245, 245), (297, 301), (310, 314)],
    "CODE_ARC": [(37, 37), (297, 314)],
}
page = open(TEMPLATE).read()
for k, r in BLOCKS.items():
    page = page.replace("{{" + k + "}}", block(r))
page = page.replace("{{CODE_PROPOSED}}", proposed_block())
page = page.replace("{{PROPOSED_DIFF}}", json.dumps(proposed_diff()).replace("</", "<\\/"))
page = page.replace("{{SRC_REL}}", SRC_REL).replace("{{SHA}}", SHA[:7])
open(OUT, "w").write(page)
print("built", OUT)
