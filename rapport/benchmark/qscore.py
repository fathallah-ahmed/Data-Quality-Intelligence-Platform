import glob
import json
import statistics as st
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).parent
FIG = Path("C:/Users/ahmed/Desktop/DataQalityWevio/rapport/figures")
NAMES = {"gemma4:cloud": "Gemma 4 (cloud)", "qwen3.5:35b": "Qwen 3.5 35B", "qwen2.5:14b-instruct": "Qwen 2.5 14B"}
ORDER = ["gemma4:cloud", "qwen3.5:35b", "qwen2.5:14b-instruct"]

data = {}
for f in glob.glob(str(HERE / "qresults_*.json")):
    r = json.loads(Path(f).read_text(encoding="utf-8"))
    data[r[0]["model"]] = r

types = [("graph", "Données\n(graphe)"), ("quality", "Qualité"), ("help", "Aide"), ("any", "Autres")]
fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 4), dpi=170, gridspec_kw={"width_ratios": [2.2, 1]})
w = 0.26
for i, m in enumerate(ORDER):
    rate = []
    for t, _ in types:
        L = [x for x in data[m] if x["expected_mode"] == t]
        rate.append(100 * sum(1 for x in L if x["ok"]) / len(L))
    a1.bar([j + (i - 1) * w for j in range(len(types))], rate, w, label=NAMES[m])
a1.set_xticks(range(len(types)))
a1.set_xticklabels([t[1] for t in types], fontsize=9)
a1.set_ylim(0, 128)
a1.set_ylabel("Cas réussis (%)")
a1.legend(fontsize=8, loc="upper center", ncol=3, frameon=False)
lat = [st.mean(x["seconds"] for x in data[m]) for m in ORDER]
bars = a2.bar([NAMES[m].replace(" ", "\n", 1) for m in ORDER], lat, color=["#4c78a8", "#f58518", "#54a24b"])
for b, v in zip(bars, lat):
    a2.text(b.get_x() + b.get_width() / 2, v + 0.6, "%.1f s" % v, ha="center", fontsize=8)
a2.set_ylabel("Latence moyenne (s)")
a2.tick_params(axis="x", labelsize=8)
for ax in (a1, a2):
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
plt.tight_layout()
plt.savefig(FIG / "eval_modeles.png")

for m in ORDER:
    r = data[m]
    c = lambda L: "%d/%d" % (sum(1 for x in L if x["ok"]), len(L))
    print(NAMES[m], c(r), [c([x for x in r if x["expected_mode"] == t]) for t, _ in types], "%.1f" % st.mean(x["seconds"] for x in r), sum(x["refused_attempts"] for x in r))
