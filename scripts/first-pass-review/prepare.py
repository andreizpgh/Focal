"""Select original first-pass windows for the frozen multilingual underlines."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import runpy

context = runpy.run_path(str(Path(__file__).resolve().parents[1] / "context-review/prepare.py"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path)
    parser.add_argument("--input", nargs=2, action="append", required=True, metavar=("MANIFEST", "PLAN"))
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    notes, windows = [], {}
    for manifest, plan in args.input:
        original = {r["id"]: r for r in json.loads(Path(manifest).read_text())}
        for n in json.loads(Path(plan).read_text()):
            note = original[n["id"]]
            folder = Path(note["draft"]).parent
            indices = {tuple(map(int, line.split()[1:])): int(line.split()[0]) for line in
                       (folder.parent / "windows").read_text().splitlines()}
            row = dict(id=n["id"], split=n["split"], source=n["source"], language=n["language"],
                       draft=note["draft"], reference=note["reference"], words=note["words"],
                       audio_sha256=note["audio_sha256"], reference_sha256=note["reference_sha256"], spans=[])
            notes.append(row)
            if not n["spans"]:
                continue
            by_time, original_windows = context["owners"](folder)
            timed, _ = context["timed_words"](note)
            for span in n["spans"]:
                owner_keys = []
                for _, a, b in timed[span["first"]:span["last"] + 1]:
                    bounds = by_time.get((a, b))
                    if bounds is None:
                        possible = [w for w in original_windows if w[0] <= (a + b) // 2 < w[1]]
                        if len(possible) == 1:
                            bounds = possible[0]
                    if bounds is None:
                        owner_keys.append(None)
                        continue
                    index = indices[bounds]
                    key = f"{n['id']}:{index}"
                    owner_keys.append(key)
                    windows[key] = (folder / "pcm", *bounds)
                row["spans"].append({**{k: span[k] for k in ("begin", "end", "first", "last")}, "owners": owner_keys})

    # Exercise a real held-out first pass even for languages without any timed flag.
    languages = defaultdict(list)
    for n in notes:
        if n["split"] == "holdout" and not n["id"].startswith("russian-"):
            languages[n["language"]].append(n)
    for rows in languages.values():
        if any(any(key for key in s["owners"]) for n in rows for s in n["spans"]):
            continue
        n = min(rows, key=lambda n: (n["source"] == "FLEURS", n["id"]))
        folder = Path(n["draft"]).parent
        first = (folder.parent / "windows").read_text().splitlines()[0]
        i, a, b = map(int, first.split())
        n["coverage_window"] = f"{n['id']}:{i}"
        windows[n["coverage_window"]] = (folder / "pcm", a, b)
    (args.work / "plan.json").write_text(json.dumps(notes, ensure_ascii=False))
    with (args.work / "requests.tsv").open("w") as out:
        for key, window in sorted(windows.items()):
            print(key, *window, sep="\t", file=out)
    print(len(notes), "notes,", sum(len(n["spans"]) for n in notes), "marked spans,", len(windows), "original windows")


if __name__ == "__main__":
    main()
