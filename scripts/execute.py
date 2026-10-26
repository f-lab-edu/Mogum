#!/usr/bin/env python3
"""
Harness Step Executor — phase 내 step을 순차 실행하고 자가 교정한다.
구현은 Codex(`codex exec`), step 마다 리뷰는 Claude(`claude -p`, 읽기 전용)가 한다.

Usage:
    python3 scripts/execute.py <phase-dir> [--push]
"""

import argparse
import contextlib
import json
import os
import subprocess
import sys
import threading
import time
import types
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parent.parent


@contextlib.contextmanager
def progress_indicator(label: str):
    """진행 표시기. with 문으로 사용하며 .elapsed 로 경과 시간을 읽는다.
    터미널이 아니면(로그 파일로 돌린 실행) 프레임 대신 시작 · 끝 줄만 쓴다 — \\r 이 줄을 덮어쓰지 못해 프레임마다 줄이 쌓인다.
    이 줄은 결과 줄과 같은 stdout 에 flush 해서 쓴다 — stderr 에 쓰면 모였다 나가는 stdout 보다 앞서 찍혀 순서가 뒤섞인다."""
    t0 = time.monotonic()
    info = types.SimpleNamespace(elapsed=0.0)
    if not sys.stderr.isatty():
        print(f"◐ {label} — 시작", flush=True)
        try:
            yield info
        finally:
            info.elapsed = time.monotonic() - t0
            print(f"◐ {label} — 끝 [{int(info.elapsed)}s]", flush=True)
        return

    frames = "◐◓◑◒"
    stop = threading.Event()

    def _animate():
        idx = 0
        while not stop.wait(0.12):
            sec = int(time.monotonic() - t0)
            sys.stderr.write(f"\r{frames[idx % len(frames)]} {label} [{sec}s]")
            sys.stderr.flush()
            idx += 1
        sys.stderr.write("\r" + " " * (len(label) + 20) + "\r")
        sys.stderr.flush()

    th = threading.Thread(target=_animate, daemon=True)
    th.start()
    try:
        yield info
    finally:
        stop.set()
        th.join()
        info.elapsed = time.monotonic() - t0


class StepExecutor:
    """Phase 디렉토리 안의 step들을 순차 실행하는 하네스."""

    MAX_RETRIES = 3
    REVIEW_ATTEMPTS = 2  # 판정을 못 얻으면(파싱 실패 · 시간 초과) 리뷰만 한 번 더 — Codex 는 다시 돌리지 않는다
    CODEX_TIMEOUT = 1800
    REVIEW_TIMEOUT = 1800
    FEAT_MSG = "feat({phase}): step {num} — {name}"
    CHORE_MSG = "chore({phase}): step {num} output"
    TZ = timezone(timedelta(hours=9))
    REVIEW_SCHEMA = {
        "type": "object",
        "properties": {
            "verdict": {"type": "string", "enum": ["approve", "request_changes"]},
            "summary": {"type": "string"},
            "findings": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["verdict", "summary", "findings"],
    }
    # 리뷰어가 쓸 수 있는 도구 — 나머지는 dontAsk 모드가 거부한다. 파일 수정 도구는 넣지 않는다.
    # step 의 AC 커맨드는 아래 형태로 써야 리뷰어가 다시 돌려볼 수 있다.
    REVIEW_TOOLS = [
        "Read", "Grep", "Glob",
        "Bash(git diff:*)", "Bash(git status:*)", "Bash(git log:*)", "Bash(git show:*)",
        "Bash(./backend/gradlew:*)", "Bash(npm --prefix frontend run:*)",
    ]

    def __init__(self, phase_dir_name: str, *, auto_push: bool = False):
        self._root = str(ROOT)
        self._phases_dir = ROOT / "phases"
        # phases/ 바로 아래에 실제로 있는 디렉터리만 고른다 — 인자를 경로에 이어 붙이지 않는다(경로 조작 방지).
        self._phase_dir = next(
            (d for d in self._phases_dir.iterdir() if d.is_dir() and d.name == phase_dir_name), None
        ) if self._phases_dir.is_dir() else None
        self._top_index_file = self._phases_dir / "index.json"
        self._auto_push = auto_push

        if self._phase_dir is None:
            print(f"ERROR: phases/{phase_dir_name} not found")
            sys.exit(1)
        self._phase_dir_name = self._phase_dir.name

        self._index_file = self._phase_dir / "index.json"
        if not self._index_file.exists():
            print(f"ERROR: {self._index_file} not found")
            sys.exit(1)

        idx = self._read_json(self._index_file)
        self._project = idx.get("project", "project")
        self._phase_name = idx.get("phase", phase_dir_name)
        self._total = len(idx["steps"])

    def run(self):
        self._checkout_branch()
        # 브랜치를 바꾸면 index 도 바뀐다 — step 수 · 막힘은 바꾼 뒤의 index 로 판단한다
        self._total = len(self._read_json(self._index_file)["steps"])
        self._print_header()
        self._check_blockers()
        self._ensure_created_at()
        self._execute_all_steps()
        self._finalize()

    # --- timestamps ---

    def _stamp(self) -> str:
        return datetime.now(self.TZ).strftime("%Y-%m-%dT%H:%M:%S%z")

    # --- JSON I/O ---

    @staticmethod
    def _read_json(p: Path) -> dict:
        return json.loads(p.read_text(encoding="utf-8"))

    @staticmethod
    def _write_json(p: Path, data: dict):
        p.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")

    # --- git ---

    def _run_git(self, *args) -> subprocess.CompletedProcess:
        cmd = ["git"] + list(args)
        return subprocess.run(cmd, cwd=self._root, capture_output=True, text=True)

    def _checkout_branch(self):
        branch = f"feat-{self._phase_name}"

        r = self._run_git("rev-parse", "--abbrev-ref", "HEAD")
        if r.returncode != 0:
            print(f"  ERROR: git을 사용할 수 없거나 git repo가 아닙니다.")
            print(f"  {r.stderr.strip()}")
            sys.exit(1)

        current = r.stdout.strip()
        if current == branch:
            return

        note = ""
        if self._run_git("rev-parse", "--verify", branch).returncode != 0:
            r = self._run_git("checkout", "-b", branch)
        elif self._run_git("merge-base", "--is-ancestor", "HEAD", branch).returncode == 0:
            r = self._run_git("checkout", branch)
        elif self._run_git("merge-base", "--is-ancestor", branch, "HEAD").returncode == 0:
            # 병합 뒤 수정 step 처럼 phase 브랜치가 현재 커밋보다 뒤다 — 앞으로만 옮기므로 잃는 커밋이 없다.
            r = self._run_git("checkout", "-B", branch)
            note = f" ({current} 의 커밋까지 앞으로 옮겼다)"
        else:
            print(f"  ERROR: '{current}' 와 '{branch}' 에 서로 없는 커밋이 있다 — 어느 쪽을 이어 갈지 정한 뒤 다시 실행하라.")
            print(f"  Hint: '{current}' 의 작업을 이어 가려면 '{branch}' 를 정리해 맞추고, 아니면 '{branch}' 에서 실행한다.")
            sys.exit(1)

        if r.returncode != 0:
            print(f"  ERROR: 브랜치 '{branch}' checkout 실패.")
            print(f"  {r.stderr.strip()}")
            print(f"  Hint: 변경사항을 stash하거나 commit한 후 다시 시도하세요.")
            sys.exit(1)

        print(f"  Branch: {branch}{note}")

    def _commit_step(self, step_num: int, step_name: str):
        output_rel = f"phases/{self._phase_dir_name}/step{step_num}-output.json"
        index_rel = f"phases/{self._phase_dir_name}/index.json"

        self._run_git("add", "-A")
        self._run_git("reset", "HEAD", "--", output_rel)
        self._run_git("reset", "HEAD", "--", index_rel)

        if self._run_git("diff", "--cached", "--quiet").returncode != 0:
            msg = self.FEAT_MSG.format(phase=self._phase_name, num=step_num, name=step_name)
            r = self._run_git("commit", "-m", msg)
            if r.returncode == 0:
                print(f"  Commit: {msg}")
            else:
                print(f"  WARN: 코드 커밋 실패: {r.stderr.strip()}")

        self._run_git("add", "-A")
        if self._run_git("diff", "--cached", "--quiet").returncode != 0:
            msg = self.CHORE_MSG.format(phase=self._phase_name, num=step_num)
            r = self._run_git("commit", "-m", msg)
            if r.returncode != 0:
                print(f"  WARN: housekeeping 커밋 실패: {r.stderr.strip()}")

    # --- top-level index ---

    def _update_top_index(self, status: str):
        if not self._top_index_file.exists():
            return
        top = self._read_json(self._top_index_file)
        ts = self._stamp()
        for phase in top.get("phases", []):
            if phase.get("dir") == self._phase_dir_name:
                phase["status"] = status
                ts_key = {"completed": "completed_at", "error": "failed_at", "blocked": "blocked_at"}.get(status)
                if ts_key:
                    phase[ts_key] = ts
                if status == "completed":
                    # 멈췄다 재개해 끝낸 phase — 이전 멈춤 기록을 남기지 않는다
                    phase.pop("failed_at", None)
                    phase.pop("blocked_at", None)
                break
        self._write_json(self._top_index_file, top)

    # --- context ---
    # 프로젝트 규칙(AGENTS.md)은 Codex 가, CLAUDE.md(→ AGENTS.md)는 Claude 가 스스로 읽는다.
    # 설계 문서는 통째로 넣지 않는다 — step 파일의 '읽어야 할 파일'이 고른다.

    @staticmethod
    def _build_step_context(index: dict) -> str:
        lines = [
            f"- Step {s['step']} ({s['name']}): {s['summary']}"
            for s in index["steps"]
            if s["status"] == "completed" and s.get("summary")
        ]
        if not lines:
            return ""
        return "## 이전 Step 산출물\n\n" + "\n".join(lines) + "\n\n"

    def _build_preamble(self, step_context: str, prev_error: Optional[str] = None,
                        resume_reason: Optional[str] = None) -> str:
        resume_section = ""
        if resume_reason:
            resume_section = (
                f"\n## ⚠ 이 step 은 이전 실행에서 멈췄다 — 사용자가 해결한 뒤 재개했다\n\n"
                f"멈춘 사유:\n{resume_reason}\n\n"
                f"이전 실행의 변경이 작업 트리에 남아 있을 수 있다. 처음부터 다시 만들지 말고 이어서 끝내라. "
                f"사용자의 해결(결정 · 수정)은 step 파일과 문서에 반영되어 있다 — step 파일을 기준으로 삼아라.\n\n---\n\n"
            )
        retry_section = ""
        if prev_error:
            retry_section = (
                f"\n## ⚠ 이전 시도 실패 — 아래 에러를 반드시 참고하여 수정하라\n\n"
                f"이전 시도의 변경은 작업 트리에 그대로 남아 있다. 처음부터 다시 만들지 말고 고쳐라.\n\n"
                f"{prev_error}\n\n---\n\n"
            )
        return (
            f"당신은 {self._project} 프로젝트의 구현 담당입니다. 아래 step을 수행하세요.\n"
            f"프로젝트 규칙은 AGENTS.md 를 따른다. 설계 문서는 step의 '읽어야 할 파일'을 먼저 읽어라.\n\n---\n\n"
            f"{step_context}{resume_section}{retry_section}"
            f"## 작업 규칙\n\n"
            f"1. 이전 step에서 작성된 코드를 확인하고 일관성을 유지하라.\n"
            f"2. 이 step에 명시된 작업만 수행하라. 추가 기능이나 파일을 만들지 마라.\n"
            f"3. 기존 테스트를 깨뜨리지 마라.\n"
            f"4. AC(Acceptance Criteria) 검증을 직접 실행하라.\n"
            f"5. phases/{self._phase_dir_name}/index.json의 해당 step status를 업데이트하라:\n"
            f"   - AC 통과 → \"completed\" + \"summary\" 필드에 이 step의 산출물을 한 줄로 요약\n"
            f"   - {self.MAX_RETRIES}회 수정 시도 후에도 실패 → \"error\" + \"error_message\" 기록\n"
            f"   - 사용자 개입이 필요한 경우 (API 키, 인증, 수동 설정, 문서와 코드가 어긋나 판단이 필요한 경우 등) → \"blocked\" + \"blocked_reason\" 기록 후 즉시 중단\n"
            f"6. 커밋하지 마라. \"completed\"로 끝내면 Claude가 변경을 리뷰하고, 승인되면 하네스가 커밋한다.\n"
            f"7. \"completed_at\" 은 쓰지 마라 — 리뷰 승인 기록이라 하네스가 쓴다.\n\n---\n\n"
        )

    # --- 에이전트 실행 (Codex · Claude 공통) ---

    def _run_agent(self, cmd: list, prompt: str, timeout: int) -> tuple:
        """(exit_code, stdout, stderr, timed_out). 시간 초과는 예외로 하네스를 죽이지 않고 실패한 시도로 돌려준다."""
        try:
            r = subprocess.run(cmd, input=prompt, cwd=self._root, capture_output=True, text=True, timeout=timeout)
            return r.returncode, r.stdout, r.stderr, False
        except subprocess.TimeoutExpired as e:
            # text=True 여도 시간 초과 때 남은 출력은 bytes 로 올 수 있다
            text = lambda v: v.decode("utf-8", "replace") if isinstance(v, bytes) else (v or "")
            return None, text(e.stdout), text(e.stderr), True

    # --- Codex 호출 ---

    def _invoke_codex(self, step: dict, preamble: str) -> dict:
        step_num, step_name = step["step"], step["name"]
        step_file = self._phase_dir / f"step{step_num}.md"

        if not step_file.exists():
            print(f"  ERROR: {step_file} not found")
            sys.exit(1)

        prompt = preamble + step_file.read_text()
        # 샌드박스를 끈다 — Gradle(~/.gradle · 네트워크)과 Testcontainers(Docker 소켓)가 workspace-write 에서 막힌다.
        # 훅 신뢰를 건너뛴다 — 승인받지 않은(또는 고친 뒤 재승인 전인) .codex/hooks.json 은 exec 에서 조용히 빠진다.
        exit_code, stdout, stderr, timed_out = self._run_agent(
            ["codex", "exec", "--dangerously-bypass-approvals-and-sandbox", "--dangerously-bypass-hook-trust",
             "--json", "-C", self._root, "-"],
            prompt, self.CODEX_TIMEOUT,
        )

        if timed_out:
            print(f"\n  WARN: Codex 가 {self.CODEX_TIMEOUT}초 안에 끝나지 않아 중단했다")
        elif exit_code != 0:
            print(f"\n  WARN: Codex가 비정상 종료됨 (code {exit_code})")
            if stderr:
                print(f"  stderr: {stderr[:500]}")

        output = {
            "step": step_num, "name": step_name,
            "exitCode": exit_code, "timedOut": timed_out,
            "stdout": stdout, "stderr": stderr,
        }
        out_path = self._phase_dir / f"step{step_num}-output.json"
        with open(out_path, "w") as f:
            json.dump(output, f, indent=2, ensure_ascii=False)

        return output

    @staticmethod
    def _usage_limit_message(output: Optional[dict]) -> Optional[str]:
        """Codex 가 사용 한도로 멈췄으면 그 메시지(다시 쓸 수 있는 시각 포함). 아니면 None.
        같은 출력에 실패가 아닌 error 이벤트(훅 신뢰 경고)도 있어 메시지 내용으로 가린다."""
        for line in ((output or {}).get("stdout") or "").splitlines():
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(event, dict):
                continue
            if event.get("type") == "error":
                message = event.get("message")
            elif event.get("type") == "turn.failed" and isinstance(event.get("error"), dict):
                message = event["error"].get("message")
            else:
                continue
            if isinstance(message, str) and "usage limit" in message.lower():
                return message
        return None

    # --- Claude 리뷰 ---

    def _review_step(self, step: dict, base: str) -> Optional[dict]:
        """step 변경을 Claude 가 읽기 전용으로 리뷰한다. 판정을 얻지 못하면 None."""
        step_num = step["step"]
        step_file = self._phase_dir / f"step{step_num}.md"
        prompt = (
            f"당신은 {self._project} 프로젝트의 리뷰어다. 구현 담당(Codex)이 방금 끝낸 step 하나를 리뷰한다.\n"
            f"파일을 고치지 마라 — 판정만 한다.\n\n"
            f"## 리뷰 대상\n\n"
            f"- 변경: `git diff {base}` 와 `git status --porcelain` 의 untracked 파일. "
            f"`phases/` 아래 하네스 메타데이터(index.json · *-output.json · *-review.json)는 제외한다.\n"
            f"- 지시: 아래 step 파일(phases/{self._phase_dir_name}/step{step_num}.md)\n\n"
            f"## 확인할 것\n\n"
            f"1. step 의 작업과 AC 를 충족하는가 — AC 커맨드를 직접 실행해 확인한다"
            f"(`./backend/gradlew -p backend ...` · `npm --prefix frontend run ...` · `git diff/status/log/show` 만 실행할 수 있다. "
            f"작업 디렉터리는 이미 저장소 루트다 — `cd` 나 `;` · `&&` 로 잇지 말고 한 번에 하나씩 실행한다. "
            f"실행하지 못한 AC 가 있으면 summary 에 적는다).\n"
            f"2. AGENTS.md 금지 목록과 작업 방식(새 기능은 테스트 먼저 — TDD)을 어기지 않았는가.\n"
            f"3. step 의 '읽어야 할 파일'에 적힌 설계 문서와 어긋나지 않는가.\n"
            f"4. step 범위를 넘는 변경(요청하지 않은 기능 · 관련 없는 리팩터링 · 포맷 변경)이 없는가.\n\n"
            f"## 판정\n\n"
            f"- approve: 위를 모두 통과. 취향 차이는 반려 사유가 아니다.\n"
            f"- request_changes: 하나라도 어김. findings 에 \"파일:줄 — 무엇이 왜 틀렸나 → 고칠 방향\" 형식으로 적는다. "
            f"구현 담당은 이 목록만 보고 고친다.\n\n---\n\n"
            f"{step_file.read_text()}"
        )
        exit_code, stdout, stderr, timed_out = self._run_agent(
            ["claude", "-p", "--permission-mode", "dontAsk",
             "--output-format", "json", "--json-schema", json.dumps(self.REVIEW_SCHEMA),
             "--allowedTools", *self.REVIEW_TOOLS],
            prompt, self.REVIEW_TIMEOUT,
        )

        review = None
        try:
            review = json.loads(stdout).get("structured_output")
        except (json.JSONDecodeError, AttributeError):
            pass
        if not isinstance(review, dict) or review.get("verdict") not in ("approve", "request_changes"):
            review = None

        out_path = self._phase_dir / f"step{step_num}-review.json"
        self._write_json(out_path, {
            "step": step_num, "base": base, "exitCode": exit_code, "timedOut": timed_out,
            "review": review, "stdout": stdout, "stderr": stderr,
        })
        return review

    def _review_with_retry(self, step: dict, base: str) -> tuple:
        """판정을 얻을 때까지 리뷰만 최대 REVIEW_ATTEMPTS 번 돌린다. (판정 또는 None, 리뷰에 쓴 초)."""
        step_num = step["step"]
        spent = 0.0
        for attempt in range(1, self.REVIEW_ATTEMPTS + 1):
            with progress_indicator(f"Step {step_num}: Claude 리뷰") as pi:
                review = self._review_step(step, base)
            spent += pi.elapsed
            if review is not None:
                return review, int(spent)
            if attempt < self.REVIEW_ATTEMPTS:
                print(f"  ↻ Step {step_num}: 리뷰 판정을 얻지 못했다 — 리뷰만 다시 한다")
        return None, int(spent)

    @staticmethod
    def _format_review(review: dict) -> str:
        findings = "\n".join(f"- {f}" for f in review.get("findings", []))
        return f"[Claude 리뷰 반려] {review.get('summary', '')}\n{findings}".rstrip()

    @staticmethod
    def _with_last_review(message: str, last_review: Optional[str]) -> str:
        """마지막 리뷰 반려 지적을 다른 실패 문구에 덧붙인다 — 반려 뒤 다른 이유로 실패해도 지적이 사라지지 않는다."""
        if not last_review or message == last_review:
            return message
        return f"{message}\n\n{last_review}"

    # --- 헤더 & 검증 ---

    def _print_header(self):
        print(f"\n{'='*60}")
        print(f"  Harness Step Executor")
        print(f"  Phase: {self._phase_name} | Steps: {self._total}")
        if self._auto_push:
            print(f"  Auto-push: enabled")
        print(f"{'='*60}")

    def _check_blockers(self):
        index = self._read_json(self._index_file)
        for s in reversed(index["steps"]):
            if s["status"] == "error":
                print(f"\n  ✗ Step {s['step']} ({s['name']}) failed.")
                print(f"  Error: {s.get('error_message', 'unknown')}")
                print(f"  Fix and reset status to 'pending' to retry.")
                sys.exit(1)
            if s["status"] == "blocked":
                print(f"\n  ⏸ Step {s['step']} ({s['name']}) blocked.")
                print(f"  Reason: {s.get('blocked_reason', 'unknown')}")
                print(f"  Resolve and reset status to 'pending' to retry.")
                sys.exit(2)
            if s["status"] != "pending":
                break

    def _ensure_created_at(self):
        index = self._read_json(self._index_file)
        if "created_at" not in index:
            index["created_at"] = self._stamp()
            self._write_json(self._index_file, index)

    # --- 실행 루프 ---

    def _take_resume_reason(self, step_num: int) -> Optional[str]:
        """이전 실행이 남긴 멈춤 사유(blocked_reason · error_message)를 꺼내고 index 에서 지운다.
        사용자가 status 만 pending 으로 되돌리면 사유가 재개한 첫 시도에 전달된다."""
        index = self._read_json(self._index_file)
        s = next(s for s in index["steps"] if s["step"] == step_num)
        blocked, error = s.pop("blocked_reason", None), s.pop("error_message", None)
        if blocked is None and error is None:
            return None
        s.pop("blocked_at", None)
        s.pop("failed_at", None)
        self._write_json(self._index_file, index)
        return "\n".join(f"[{kind}] {text}" for kind, text in (("blocked", blocked), ("error", error)) if text)

    def _execute_single_step(self, step: dict, review_only: bool = False) -> bool:
        """단일 step 실행 (재시도 포함). Codex 구현 → Claude 리뷰 승인 시 커밋하고 True.
        review_only — Codex 는 끝냈지만 리뷰 전에 하네스가 멈춘 step. 첫 시도는 Codex 없이 리뷰부터 한다."""
        step_num, step_name = step["step"], step["name"]
        done = sum(1 for s in self._read_json(self._index_file)["steps"] if s["status"] == "completed")
        # 리뷰는 이 step 이 시작된 커밋부터의 변경 전체를 본다 — 재시도분과 Codex 가 임의로 한 커밋까지.
        base = self._run_git("rev-parse", "HEAD").stdout.strip()
        prev_error = None
        last_review = None  # 마지막 리뷰 반려 지적 — 뒤이은 다른 실패에 덮이지 않게 따로 든다
        resume_reason = self._take_resume_reason(step_num)

        for attempt in range(1, self.MAX_RETRIES + 1):
            elapsed, timed_out = 0, False
            if review_only and attempt == 1:
                print(f"  ↺ Step {step_num}: {step_name} — 리뷰 전에 멈춘 step 이다. 리뷰부터 다시 한다")
            else:
                index = self._read_json(self._index_file)
                step_context = self._build_step_context(index)
                preamble = self._build_preamble(step_context, prev_error, resume_reason)

                tag = f"Step {step_num}/{self._total - 1} ({done} done): {step_name}"
                if attempt > 1:
                    tag += f" [retry {attempt}/{self.MAX_RETRIES}]"

                with progress_indicator(tag) as pi:
                    output = self._invoke_codex(step, preamble)
                elapsed = int(pi.elapsed)  # progress_indicator 는 블록이 끝날 때 elapsed 를 채운다
                timed_out = bool(output and output.get("timedOut"))

                # 사용 한도는 다시 시도해도 같다 — 남은 시도를 쓰지 않고 멈춘다
                limit = self._usage_limit_message(output)
                if limit:
                    self._block_step(step_num, step_name, elapsed,
                                     self._with_last_review(f"Codex 사용 한도 — {limit}", last_review))
                self._drop_completed_at(step_num)

            index = self._read_json(self._index_file)
            status = next((s.get("status", "pending") for s in index["steps"] if s["step"] == step_num), "pending")

            if status == "completed":
                review, review_elapsed = self._review_with_retry(step, base)
                if review is None:
                    self._fail_step(step_num, step_name,
                                    f"Claude 리뷰 판정을 얻지 못했다 — phases/{self._phase_dir_name}/step{step_num}-review.json 확인")
                if review["verdict"] == "approve":
                    for s in index["steps"]:
                        if s["step"] == step_num:
                            s["completed_at"] = self._stamp()
                    self._write_json(self._index_file, index)
                    self._commit_step(step_num, step_name)
                    print(f"  ✓ Step {step_num}: {step_name} [{elapsed}s · 리뷰 {review_elapsed}s] — 리뷰 승인")
                    return True
                err_msg = self._format_review(review)
                last_review = err_msg

            elif status == "blocked":
                self._block_step(step_num, step_name, elapsed)

            elif timed_out:
                err_msg = (f"Codex 가 {self.CODEX_TIMEOUT}초 안에 끝나지 않아 중단됐다 — "
                           f"작업 트리에 남은 변경을 이어서 끝내고 status 를 갱신하라")

            else:
                err_msg = next(
                    (s.get("error_message", "Step did not update status") for s in index["steps"] if s["step"] == step_num),
                    "Step did not update status",
                )

            if attempt < self.MAX_RETRIES:
                for s in index["steps"]:
                    if s["step"] == step_num:
                        s["status"] = "pending"
                        s.pop("error_message", None)
                        s.pop("summary", None)
                self._write_json(self._index_file, index)
                prev_error = self._with_last_review(err_msg, last_review)
                print(f"  ↻ Step {step_num}: retry {attempt}/{self.MAX_RETRIES} — {err_msg.splitlines()[0]}")
            else:
                self._fail_step(step_num, step_name,
                                f"[{self.MAX_RETRIES}회 시도 후 실패] {self._with_last_review(err_msg, last_review)}")

        return False  # unreachable

    def _drop_completed_at(self, step_num: int):
        """Codex 가 쓴 completed_at 을 지운다 — 리뷰 승인 기록이라 하네스만 쓴다.
        남겨 두면 리뷰 중에 하네스가 끊겼을 때 재실행이 리뷰를 건너뛴다."""
        index = self._read_json(self._index_file)
        s = next(s for s in index["steps"] if s["step"] == step_num)
        if s.pop("completed_at", None) is not None:
            self._write_json(self._index_file, index)

    def _block_step(self, step_num: int, step_name: str, elapsed: int, reason: Optional[str] = None):
        """step 을 blocked 로 표시하고 멈춘다. reason 이 없으면 Codex 가 적은 blocked_reason 을 쓴다."""
        index = self._read_json(self._index_file)
        s = next(s for s in index["steps"] if s["step"] == step_num)
        s["status"] = "blocked"
        if reason is not None:
            s["blocked_reason"] = reason
        s["blocked_at"] = self._stamp()
        self._write_json(self._index_file, index)
        print(f"  ⏸ Step {step_num}: {step_name} blocked [{elapsed}s]")
        print(f"    Reason: {s.get('blocked_reason', '')}")
        self._update_top_index("blocked")
        sys.exit(2)

    def _fail_step(self, step_num: int, step_name: str, err_msg: str):
        """step 을 error 로 표시하고 멈춘다. 변경은 커밋하지 않는다 — 재실행 때 리뷰가 이 변경까지 다시 본다."""
        index = self._read_json(self._index_file)
        for s in index["steps"]:
            if s["step"] == step_num:
                s["status"] = "error"
                s["error_message"] = err_msg
                s["failed_at"] = self._stamp()
        self._write_json(self._index_file, index)
        print(f"  ✗ Step {step_num}: {step_name} failed")
        print(f"    Error: {err_msg}")
        self._update_top_index("error")
        sys.exit(1)

    @staticmethod
    def _next_step(index: dict) -> Optional[dict]:
        """다음에 할 step — pending, 또는 completed 인데 completed_at(리뷰 승인 기록)이 없는 step.
        후자는 Codex 가 끝낸 뒤 리뷰 · 커밋 전에 하네스가 멈춘 경우다. 건너뛰면 리뷰 없이 다음 step 커밋에 섞인다."""
        return next((s for s in index["steps"]
                     if s["status"] == "pending" or (s["status"] == "completed" and "completed_at" not in s)), None)

    def _stop_file(self) -> Path:
        """멈춤 요청 파일 — .git 안에 둬서 커밋 · .gitignore 와 무관하다."""
        r = self._run_git("rev-parse", "--git-path", "harness-stop")
        rel = r.stdout.strip() if r.returncode == 0 and r.stdout.strip() else ".git/harness-stop"
        return Path(self._root) / rel

    def _execute_all_steps(self):
        while True:
            index = self._read_json(self._index_file)
            nxt = self._next_step(index)
            if nxt is None:
                print("\n  All steps completed!")
                return

            step_num = nxt["step"]
            stop = self._stop_file()
            if stop.exists():
                stop.unlink()
                print(f"\n  ⏸ 멈춤 요청({stop.name}) — Step {step_num} 앞에서 멈춘다. 이어 하려면 다시 실행한다.")
                sys.exit(0)

            for s in index["steps"]:
                if s["step"] == step_num and "started_at" not in s:
                    s["started_at"] = self._stamp()
                    self._write_json(self._index_file, index)
                    break

            self._execute_single_step(nxt, review_only=nxt["status"] == "completed")

    def _finalize(self):
        index = self._read_json(self._index_file)
        index["completed_at"] = self._stamp()
        self._write_json(self._index_file, index)
        self._update_top_index("completed")

        self._run_git("add", "-A")
        if self._run_git("diff", "--cached", "--quiet").returncode != 0:
            msg = f"chore({self._phase_name}): mark phase completed"
            r = self._run_git("commit", "-m", msg)
            if r.returncode == 0:
                print(f"  ✓ {msg}")

        if self._auto_push:
            branch = f"feat-{self._phase_name}"
            r = self._run_git("push", "-u", "origin", branch)
            if r.returncode != 0:
                print(f"\n  ERROR: git push 실패: {r.stderr.strip()}")
                sys.exit(1)
            print(f"  ✓ Pushed to origin/{branch}")

        print(f"\n{'='*60}")
        print(f"  Phase '{self._phase_name}' completed!")
        print(f"{'='*60}")


def main():
    parser = argparse.ArgumentParser(description="Harness Step Executor")
    parser.add_argument("phase_dir", help="Phase directory name (e.g. 0-mvp)")
    parser.add_argument("--push", action="store_true", help="Push branch after completion")
    args = parser.parse_args()

    StepExecutor(args.phase_dir, auto_push=args.push).run()


if __name__ == "__main__":
    main()
