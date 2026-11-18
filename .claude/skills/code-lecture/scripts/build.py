"""설명 페이지 조립 — lecture.json 의 코드 발췌를 실제 파일에서 잘라 넣고, 내용을 검사한 뒤 player.html 에 담는다.

사용: python3 -I build.py <lecture.json> <out.html> [--repo <저장소 루트>]

- 발췌는 lecture.json 의 source.rev(커밋)에서 `git show` 로 읽는다. rev 가 없으면 작업 트리 파일을 읽는다.
- 검사에 걸리면 무엇이 틀렸는지 모두 찍고 종료 코드 1 로 끝난다(페이지를 쓰지 않는다). 경고는 쓰고 알린다.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

PLAYER = Path(__file__).resolve().parent.parent / "assets" / "player.html"
VIEW_W, VIEW_H = 640, 380
LANG = {
    ".java": "java", ".kt": "java", ".sql": "sql", ".yml": "yaml", ".yaml": "yaml",
    ".ts": "ts", ".tsx": "ts", ".js": "ts", ".jsx": "ts", ".py": "py",
}
NODE_KINDS = {"svc", "db", "ext", "err", "ok", "note", "later", "box", "held", "free", "slack", "cap"}
EDGE_KINDS = {None, "dash", "err", "mark"}


def read_file(repo, rev, path):
    if rev:
        done = subprocess.run(["git", "-C", repo, "show", f"{rev}:{path}"], capture_output=True, text=True)
        if done.returncode != 0:
            raise ValueError(f"git show {rev}:{path} 실패 — {done.stderr.strip()}")
        return done.stdout
    return (Path(repo) / path).read_text(encoding="utf-8")


def cut(lines, spec, key):
    """lines 는 0부터. 돌려주는 start 는 1부터인 줄 번호."""
    if "lines" in spec:
        a, b = spec["lines"]
        if not (1 <= a <= b <= len(lines)):
            raise ValueError(f"snippet '{key}': lines {a}~{b} 가 파일 범위(1~{len(lines)}) 밖이다")
        return a, lines[a - 1:b]
    start = next((i for i, line in enumerate(lines) if re.search(spec["from"], line)), None)
    if start is None:
        raise ValueError(f"snippet '{key}': from /{spec['from']}/ 에 맞는 줄이 없다")
    end = next((i for i in range(start + 1, len(lines)) if re.search(spec["to"], lines[i])), None)
    if end is None:
        raise ValueError(f"snippet '{key}': {start + 1}행 뒤에 to /{spec['to']}/ 에 맞는 줄이 없다")
    if spec.get("to_exclusive"):
        end -= 1
        while end > start and not lines[end].strip():
            end -= 1
    start = max(0, start + spec.get("from_offset", 0))
    return start + 1, lines[start:end + 1]


def overlap(a, b):
    return a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"] and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"]


def main():
    args = sys.argv[1:]
    repo = "."
    if "--repo" in args:
        i = args.index("--repo")
        repo = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    src, out = Path(args[0]), Path(args[1])
    lecture = json.loads(src.read_text(encoding="utf-8"))
    errors, warnings = [], []

    rev = (lecture.get("source") or {}).get("rev")
    snippets = {}
    for key, spec in lecture.get("snippets", {}).items():
        try:
            path = spec["file"]
            lines = read_file(repo, spec.get("rev", rev), path).split("\n")
            start, part = cut(lines, spec, key)
            if not any(line.strip() for line in part):
                raise ValueError(f"snippet '{key}': 잘라낸 줄이 모두 비었다")
            if len(part) > 120:
                warnings.append(f"snippet '{key}': {len(part)}줄 — 길다. 단계가 가리키는 부분만 자르는 편이 읽기 쉽다")
            snippets[key] = {"file": path, "start": start, "lang": LANG.get(Path(path).suffix, "text"), "lines": part}
        except (KeyError, ValueError, OSError) as exc:
            errors.append(str(exc) if not isinstance(exc, KeyError) else f"snippet '{key}': 칸 {exc} 가 없다")

    lists = lecture.get("lists", {})
    used = set()
    chapters = lecture.get("chapters") or []
    if not lecture.get("title"):
        errors.append("title 이 없다")
    if not chapters:
        errors.append("chapters 가 비었다")

    beats_total, detail_chars = 0, 0
    for ci, ch in enumerate(chapters, 1):
        where = f"{ci}장 '{ch.get('title', '?')}'"
        nodes = ch.get("nodes", [])
        ids = [n.get("id") for n in nodes]
        for dup in {i for i in ids if ids.count(i) > 1}:
            errors.append(f"{where}: 노드 id '{dup}' 가 겹친다")
        for n in nodes:
            k = n.get("k", "svc")
            if k not in NODE_KINDS:
                errors.append(f"{where}: 노드 '{n.get('id')}' 의 k '{k}' 는 없는 종류다 — {sorted(NODE_KINDS)}")
            if k == "cap":
                continue
            if any(c not in n for c in ("x", "y", "w", "h")):
                errors.append(f"{where}: 노드 '{n.get('id')}' 에 x · y · w · h 가 다 있어야 한다")
                continue
            if n["x"] < 0 or n["y"] < 0 or n["x"] + n["w"] > VIEW_W or n["y"] + n["h"] > VIEW_H:
                errors.append(f"{where}: 노드 '{n['id']}' 가 도식 영역 {VIEW_W}×{VIEW_H} 밖으로 나간다")
        solid = [n for n in nodes if n.get("k", "svc") not in ("box", "cap") and all(c in n for c in "xywh")]
        for i, a in enumerate(solid):
            for b in solid[i + 1:]:
                if overlap(a, b):
                    warnings.append(f"{where}: 노드 '{a['id']}' 와 '{b['id']}' 가 겹친다")
        node_ids = set(ids)
        edge_ids = []
        for ed in ch.get("edges", []):
            eid = ed.get("id") or f"{ed.get('f')}-{ed.get('t')}"
            edge_ids.append(eid)
            if ed.get("k") not in EDGE_KINDS:
                errors.append(f"{where}: 선 '{eid}' 의 k '{ed.get('k')}' 는 없는 종류다")
            if "d" not in ed and (ed.get("f") not in node_ids or ed.get("t") not in node_ids):
                errors.append(f"{where}: 선 '{eid}' 의 f · t 가 이 장의 노드가 아니다(경로를 직접 쓰면 d 와 id 를 준다)")
        for dup in {i for i in edge_ids if edge_ids.count(i) > 1}:
            errors.append(f"{where}: 선 id '{dup}' 가 겹친다 — 같은 두 노드 사이 선이 둘이면 id 를 따로 준다")

        beats = ch.get("beats") or []
        if not beats:
            errors.append(f"{where}: beats 가 비었다")
        code, hl = None, []
        for bi, beat in enumerate(beats, 1):
            at = f"{where} {bi}단계"
            beats_total += 1
            if not beat.get("say"):
                errors.append(f"{at}: say 가 없다")
            detail = beat.get("detail")
            if not detail:
                errors.append(f"{at}: detail 이 없다 — 이 페이지의 핵심이 자세한 설명이다")
            else:
                for p in detail:
                    detail_chars += sum(len(x) for x in p) if isinstance(p, list) else len(p)
            for nid in beat.get("on", []):
                if nid not in node_ids:
                    errors.append(f"{at}: on '{nid}' 는 이 장의 노드가 아니다")
            for eid in beat.get("e", []):
                if eid not in edge_ids:
                    errors.append(f"{at}: e '{eid}' 는 이 장의 선이 아니다 — 선 id 는 'f-t' 또는 직접 준 id")
            if "code" in beat:
                code, hl = beat["code"], beat.get("hl", [])
            elif "hl" in beat:
                hl = beat["hl"]
            if code is None:
                if hl:
                    errors.append(f"{at}: 코드가 없는데 hl 이 있다")
                continue
            if code.startswith("list:"):
                if code[5:] not in lists:
                    errors.append(f"{at}: lists 에 '{code[5:]}' 가 없다")
                elif hl:
                    errors.append(f"{at}: 요약 목록에는 hl 을 쓰지 않는다")
                continue
            used.add(code)
            if code not in snippets:
                if code not in lecture.get("snippets", {}):
                    errors.append(f"{at}: snippets 에 '{code}' 가 없다")
                continue
            for h in hl:
                if not any(h in line for line in snippets[code]["lines"]):
                    errors.append(f"{at}: hl '{h}' 가 snippet '{code}' 의 어느 줄에도 없다")

    for key in snippets.keys() - used:
        warnings.append(f"snippet '{key}': 어느 단계도 쓰지 않는다")

    for w in warnings:
        print("경고:", w)
    if errors:
        for e in errors:
            print("오류:", e)
        sys.exit(1)

    page = dict(lecture)
    page["snippets"] = snippets
    payload = json.dumps(page, ensure_ascii=False).replace("</", "<\\/")
    title = lecture["title"].replace("&", "&amp;").replace("<", "&lt;")
    html = PLAYER.read_text(encoding="utf-8").replace("__TITLE__", title, 1).replace("/*__LECTURE__*/null", payload, 1)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    minutes = round(detail_chars / 450)
    print(f"완료: {out} — {len(chapters)}장 · {beats_total}단계 · 발췌 {len(snippets)}개 · "
          f"설명 {detail_chars:,}자(읽는 데 약 {minutes}분) · {len(html) / 1024:.0f}KB")


if __name__ == "__main__":
    main()
