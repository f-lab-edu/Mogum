"""Stitch 시안을 받아 design/ 에 둔다 — 화면 HTML · 디자인 가이드 · 디자인 시스템 · 마스코트.

사용자가 시안을 다시 가져올 때만 쓴다. 사용: python3 tools/fetch.py (design/ 에서)
API 키는 ~/.claude.json 의 Stitch MCP 설정에서 읽고 출력하지 않는다 — AGENTS.md 「API 키를 읽지 않는다」의
예외로 2026-11-07 사용자가 승인했다. 이 스크립트 밖에서 키를 읽지 않는다.

화면을 더하거나 빼면 SCREENS 를 고치고 README 의 「화면별 시안」 표도 맞춘다.
화면 ID 는 Stitch 에서 고쳐도 그대로이고, HTML 파일은 고칠 때마다 새로 생긴다 — 그래서 화면 ID 로 찾는다.
"""
import base64
import json
import os
import urllib.request
from pathlib import Path

PROJECT = "14607858306264319539"
API = f"https://stitch.googleapis.com/v1/projects/{PROJECT}"
DESIGN = Path(__file__).resolve().parent.parent

# 이름 → Stitch 화면 ID
SCREENS = {
    "01-login": "6b0d130e97be44fda758cedef1d9a53a",
    "02-apply": "6923b79246024a1a95e93274fcb1ee8a",
    "03-waiting-approval": "88e6f46cf60849e196c2e58cf3e00138",
    "04-rejected": "7a3c3d25e4414578aa81dd308fefe460",
    "05-home": "f5d0cbdb250942cf861baef69f5cc8b3",
    "06-new-course": "89e9870750c440cf80e72212c837c0d7",
    "07-waiting": "29d672867f054667aecad1cba2f116a0",
    "08-course-review": "7f9b2469d74246babf89fd23c6e8821a",
    "09-course-learning": "9e8b9f59d62648b885aecdb58f43881e",
    "10-course-done": "b8ce05e099df4be180ae01f81240c636",
    "11-roadmap": "6e07a0513db54d4ea449818f1e210be7",
    "12-lecture": "23716516e3564210a34a9ae057ae2a27",
    "13-celebration": "9297fa9a582b491994837ec8cdf0178e",
    "14-review-front": "e0344e5cff5b4be68a69618e1d6664a2",
    "15-review-answer": "2a92b94ac72a4ea180cf710702c6ec2b",
    "16-review-empty": "8e7a74c205b947b4a126c5ff743ee6da",
    "17-courses": "0c6337c63acd4d05ac0b2a317e4ccba9",
    "18-roadmaps": "44b1ce33cca049e5a00fe486a4daaef4",
    "19-privacy": "c9467683452a4fb3a0f84823dbe625de",
    "flow": "8b736a5cf686419a831849da0ebbbc21",
}
GUIDE = "528b3075ee874ea5959ed8f473d058a6"  # 머금 UI 디자인 가이드 & 컴포넌트 명세서
# 모구미 이미지 — 파일 이름 → Stitch 화면 ID. 사용자가 파일로 준 이미지도 assets/ 에 있으니 assets/ 는 비우지 않는다
ASSETS = {
    "mogumi-ai-chip.png": "675991803702356627",
    "mogumi-reading.png": "14629317761204814570",
}
# 보이지만 가져오지 않는 화면 — 로그인과 같은 HTML · 06 의 전신 사본 · 노트북 모구미(쓰지 않는다 — 사용자 결정 2026-11-07)
SKIPPED = {"3b2d1f9e7d6e4fa18969a5cc3fdd4551", "9705003143454390616", "9312149284136211000"}


def stitch_key():
    cfg = json.loads(Path("~/.claude.json").expanduser().read_text())
    servers = [cfg.get("mcpServers", {})] + [p.get("mcpServers", {}) for p in cfg.get("projects", {}).values()]
    for s in servers:
        key = s.get("stitch", {}).get("headers", {}).get("X-Goog-Api-Key")
        if key:
            return key
    raise SystemExit("~/.claude.json 에 Stitch MCP 설정이 없다 — claude mcp add stitch 로 먼저 등록한다")


def get(url, key):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"X-Goog-Api-Key": key})) as r:
        return json.loads(r.read())


def clear(folder, suffix):
    folder.mkdir(exist_ok=True)
    for f in folder.glob("*" + suffix):
        f.unlink()


def main():
    key = stitch_key()
    project = get(API, key)
    (DESIGN / "DESIGN.md").write_text(project["designTheme"]["designMd"], encoding="utf-8")

    clear(DESIGN / "html", ".html")
    for name, sid in SCREENS.items():
        screen = get(f"{API}/screens/{sid}", key)
        html = get(screen["htmlCode"]["downloadUrl"], key)["dataString"]
        (DESIGN / "html" / f"{name}.html").write_text(html, encoding="utf-8")
        print(f"html/{name}.html  ← {screen['title']}")

    guide = get(f"{API}/screens/{GUIDE}", key)
    (DESIGN / "guide.md").write_text(get(guide["htmlCode"]["downloadUrl"], key)["dataString"], encoding="utf-8")

    (DESIGN / "assets").mkdir(exist_ok=True)
    for name, sid in ASSETS.items():
        screen = get(f"{API}/screens/{sid}", key)
        data = get(screen["screenshot"]["downloadUrl"], key)["dataString"]
        (DESIGN / "assets" / name).write_bytes(base64.b64decode(data))

    known = set(SCREENS.values()) | set(ASSETS.values()) | {GUIDE} | SKIPPED
    for inst in project.get("screenInstances", []):
        sid = inst.get("sourceScreen", "").rsplit("/", 1)[-1]
        if sid and not inst.get("hidden") and sid not in known:
            print(f"가져오지 않은 화면: {inst.get('label')} ({sid}) — 필요하면 SCREENS 에 더한다")


if __name__ == "__main__":
    main()
