#!/usr/bin/env python3
"""
TDD 가드 — Claude Code · Codex 공통 훅. 두 도구 모두 exit 2 + stderr 를 '막고 이유를 에이전트에 전달'로 다룬다.

Usage (stdin: 훅 입력 JSON):
    python3 scripts/tdd_guard.py pre    # PreToolUse — 짝 테스트 파일이 먼저 있어야 구현 파일을 쓴다
    python3 scripts/tdd_guard.py stop   # Stop — 이번 세션이 구현 파일을 바꿨으면 테스트 변경도 있어야 끝낸다

pre 가 허용한 파일은 세션별로 `.git/tdd-guard/{session_id}.json` 에 남긴다.
stop 은 그 기록만 본다 — 작업 트리에 남은 다른 세션(예: 실패한 Codex step)의 변경으로 이 세션을 막지 않는다.
Bash 로 쓴 파일은 잡지 못한다. 그 구멍은 execute.py 의 Claude 리뷰가 메운다.
"""

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

ROOT = Path(__file__).resolve().parent.parent

JAVA_MAIN = "backend/src/main/java/"
JAVA_TEST = "backend/src/test/java/"
WEB_SRC = "frontend/src/"
WEB_TEST_SUPPORT = "frontend/src/test/"
WEB_TEST_NAME = re.compile(r"\.(test|spec)\.tsx?$")
# 동작이 없는 Java 타입 — 짝 테스트를 요구하지 않는다
JAVA_ENTITY = re.compile(r"^\s*@(Entity|Embeddable)\b", re.M)


# --- 분류 ---

def is_test(rel: str) -> bool:
    if rel.startswith(JAVA_TEST):
        return rel.endswith(".java")
    if rel.startswith(WEB_SRC) and rel.endswith((".ts", ".tsx")):
        return bool(WEB_TEST_NAME.search(rel)) or "/__tests__/" in rel
    return False


def is_production(rel: str) -> bool:
    if rel.startswith(JAVA_MAIN):
        return rel.endswith(".java")
    if rel.startswith(WEB_SRC) and rel.endswith((".ts", ".tsx")):
        return not is_test(rel) and not rel.startswith(WEB_TEST_SUPPORT)
    return False


def is_exempt(root: Path, rel: str, content: Optional[str]) -> bool:
    """package-info · 진입점 · 타입 전용 파일, 그리고 interface · record · enum · 예외 · @Entity."""
    name = rel.rsplit("/", 1)[-1]
    if name in ("package-info.java", "module-info.java", "main.tsx", "types.ts"):
        return True
    if name.endswith(("Application.java", ".d.ts", ".types.ts")):
        return True
    if not rel.endswith(".java"):
        return False

    if content is None:
        path = root / rel
        content = path.read_text(encoding="utf-8") if path.exists() else ""
    stem = name[:-len(".java")]
    decl = re.search(rf"\b(class|interface|record|enum)\s+{re.escape(stem)}\b", content)
    if decl and decl.group(1) != "class":
        return True
    if decl:
        header = content[decl.end():content.find("{", decl.end())]
        if re.search(r"\bextends\s+[\w.]*(Exception|Error)\b", header):
            return True
    return bool(JAVA_ENTITY.search(content))


def suggested_test(rel: str) -> str:
    if rel.startswith(JAVA_MAIN):
        return JAVA_TEST + rel[len(JAVA_MAIN):-len(".java")] + "Test.java"
    base, ext = rel.rsplit(".", 1)
    return f"{base}.test.{ext}"


def has_test(root: Path, rel: str, touched: Iterable[str]) -> bool:
    """짝 테스트가 디스크에 있거나 같은 호출에서 함께 쓰이는가."""
    if rel.startswith(JAVA_MAIN):
        sub = rel[len(JAVA_MAIN):-len(".java")]
        test_dir, stem = (JAVA_TEST + sub).rsplit("/", 1)
        pattern = re.compile(rf"^{re.escape(stem)}\w*(Test|Tests|IT)\.java$")
        dirs = [test_dir]
    else:
        src_dir, name = rel.rsplit("/", 1)
        stem = name.rsplit(".", 1)[0]
        pattern = re.compile(rf"^{re.escape(stem)}\.(test|spec)\.tsx?$")
        dirs = [src_dir, f"{src_dir}/__tests__"]

    for t in touched:
        d, n = t.rsplit("/", 1) if "/" in t else ("", t)
        if d in dirs and pattern.match(n):
            return True
    for d in dirs:
        path = root / d
        if path.is_dir() and any(pattern.match(p.name) for p in path.iterdir()):
            return True
    return False


# --- 훅 입력 ---

def parse_patch(text: str) -> List[Tuple[str, Optional[str]]]:
    """Codex apply_patch 본문 → [(경로, 새 파일 내용 또는 None)]. 삭제는 뺀다."""
    files = []  # [경로, 줄 목록(Add) 또는 None(Update)]
    current = None
    for line in text.splitlines():
        if line.startswith("*** Add File: "):
            current = [line[len("*** Add File: "):].strip(), []]
            files.append(current)
        elif line.startswith("*** Update File: "):
            current = [line[len("*** Update File: "):].strip(), None]
            files.append(current)
        elif line.startswith("*** Move to: ") and current is not None:
            current[0] = line[len("*** Move to: "):].strip()
        elif line.startswith("*** "):
            current = None
        elif current is not None and current[1] is not None and line.startswith("+"):
            current[1].append(line[1:])
    return [(p, "\n".join(c) + "\n" if c is not None else None) for p, c in files]


def touched_files(payload: dict) -> List[Tuple[str, Optional[str]]]:
    tool = payload.get("tool_name", "")
    tool_input = payload.get("tool_input") or {}
    if tool == "apply_patch":  # Codex
        command = tool_input.get("command") or ""
        if isinstance(command, list):
            command = "\n".join(command)
        return parse_patch(command)
    if tool in ("Write", "Edit", "MultiEdit") and tool_input.get("file_path"):  # Claude
        return [(tool_input["file_path"], tool_input.get("content") if tool == "Write" else None)]
    return []


def to_rel(path: str, cwd: Optional[str], root: Path) -> Optional[str]:
    p = Path(path)
    if not p.is_absolute():
        p = Path(cwd or root) / p
    try:
        return p.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return None


# --- 세션 기록 ---

def _state_file(root: Path, session_id: str) -> Path:
    git_path = subprocess.run(["git", "rev-parse", "--git-path", "tdd-guard"],
                              cwd=root, capture_output=True, text=True).stdout.strip()
    return root / git_path / (re.sub(r"[^\w.-]", "_", session_id) + ".json")


def _load(root: Path, session_id: Optional[str]) -> List[str]:
    if not session_id:
        return []
    f = _state_file(root, session_id)
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else []


def _record(root: Path, session_id: Optional[str], paths: List[str]):
    if not session_id or not paths:
        return
    f = _state_file(root, session_id)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(sorted(set(_load(root, session_id)) | set(paths))), encoding="utf-8")


def _changed_in_worktree(root: Path) -> List[str]:
    out = subprocess.run(["git", "status", "--porcelain", "-uall", "-z"],
                         cwd=root, capture_output=True, text=True).stdout
    entries = out.split("\0")
    paths, i = [], 0
    while i < len(entries):
        entry = entries[i]
        if len(entry) > 3:
            paths.append(entry[3:])
            if entry[0] in "RC":  # 이름 바꾸기 · 복사는 원래 경로가 다음 항목에 온다
                i += 1
        i += 1
    return paths


# --- 훅 ---

def pre(payload: dict, root: Path) -> Optional[str]:
    files = []
    for path, content in touched_files(payload):
        rel = to_rel(path, payload.get("cwd"), root)
        if rel:
            files.append((rel, content))
    touched = [rel for rel, _ in files]

    missing = [rel for rel, content in files
               if is_production(rel) and not is_exempt(root, rel, content) and not has_test(root, rel, touched)]
    if missing:
        lines = "\n".join(f"- {rel} → 먼저 {suggested_test(rel)}" for rel in missing)
        return (
            "TDD 가드: 짝 테스트 없이 구현 파일을 쓰려 했다. 테스트를 먼저 쓰고 실패를 확인한 뒤 구현하라(AGENTS.md 작업 방식).\n"
            f"{lines}\n"
            "테스트와 구현을 한 패치에 함께 넣어도 된다. 동작이 없는 타입(interface · record · enum · 예외 · @Entity)은 검사하지 않는다."
        )
    _record(root, payload.get("session_id"), touched)
    return None


def stop(payload: dict, root: Path) -> Optional[str]:
    if payload.get("stop_hook_active"):  # 한 번 되돌린 뒤에는 막지 않는다 — 무한 반복 방지
        return None
    touched = _load(root, payload.get("session_id"))
    changed = [rel for rel in touched
               if is_production(rel) and (root / rel).exists() and not is_exempt(root, rel, None)]
    if not changed:
        return None
    if any(is_test(rel) for rel in touched) or any(is_test(rel) for rel in _changed_in_worktree(root)):
        return None
    lines = "\n".join(f"- {rel}" for rel in changed)
    return (
        "TDD 가드: 이번 세션에서 구현 파일을 바꿨는데 테스트 변경이 없다.\n"
        f"{lines}\n"
        "바뀐 동작을 검증하는 테스트를 추가하거나 고쳐라. 동작이 바뀌지 않은 변경(리팩터링 등)이면 그 근거를 답변에 적고 다시 끝내라."
    )


def main(argv: List[str]) -> int:
    hooks = {"pre": pre, "stop": stop}
    if len(argv) < 2 or argv[1] not in hooks:
        print(f"usage: {argv[0]} pre|stop  (stdin: hook JSON)", file=sys.stderr)
        return 1
    message = hooks[argv[1]](json.load(sys.stdin), ROOT)
    if message:
        print(message, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
