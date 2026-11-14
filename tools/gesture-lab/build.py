import html
import re
import subprocess
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
    return f'<span class="ln">{n}</span>{s}'


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
page = page.replace("{{SRC_REL}}", SRC_REL).replace("{{SHA}}", SHA[:7])
open(OUT, "w").write(page)
print("built", OUT)
