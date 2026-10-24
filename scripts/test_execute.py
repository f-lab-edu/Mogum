"""
execute.py 리팩터링 안전망 테스트.
리팩터링 전후 동작이 동일한지 검증한다.
"""

import contextlib
import json
import os
import subprocess
import sys
import textwrap
import types
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import execute as ex


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def tmp_project(tmp_path):
    """phases/ 를 갖춘 임시 프로젝트 구조."""
    phases_dir = tmp_path / "phases"
    phases_dir.mkdir()
    return tmp_path


@pytest.fixture
def phase_dir(tmp_project):
    """step 3개를 가진 phase 디렉토리."""
    d = tmp_project / "phases" / "0-mvp"
    d.mkdir()

    index = {
        "project": "TestProject",
        "phase": "mvp",
        "steps": [
            {"step": 0, "name": "setup", "status": "completed", "summary": "프로젝트 초기화 완료",
             "completed_at": "2026-10-12T06:00:00+0900"},
            {"step": 1, "name": "core", "status": "completed", "summary": "핵심 로직 구현",
             "completed_at": "2026-10-12T06:10:00+0900"},
            {"step": 2, "name": "ui", "status": "pending"},
        ],
    }
    (d / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False))
    (d / "step2.md").write_text("# Step 2: UI\n\nUI를 구현하세요.")

    return d


@pytest.fixture
def top_index(tmp_project):
    """phases/index.json (top-level)."""
    top = {
        "phases": [
            {"dir": "0-mvp", "status": "pending"},
            {"dir": "1-polish", "status": "pending"},
        ]
    }
    p = tmp_project / "phases" / "index.json"
    p.write_text(json.dumps(top, indent=2))
    return p


@pytest.fixture
def executor(tmp_project, phase_dir):
    """테스트용 StepExecutor 인스턴스. git 호출은 별도 mock 필요."""
    with patch.object(ex, "ROOT", tmp_project):
        inst = ex.StepExecutor("0-mvp")
    # 내부 경로를 tmp_project 기준으로 재설정
    inst._root = str(tmp_project)
    inst._phases_dir = tmp_project / "phases"
    inst._phase_dir = phase_dir
    inst._phase_dir_name = "0-mvp"
    inst._index_file = phase_dir / "index.json"
    inst._top_index_file = tmp_project / "phases" / "index.json"
    return inst


# ---------------------------------------------------------------------------
# _stamp (= 이전 now_iso)
# ---------------------------------------------------------------------------

class TestStamp:
    def test_returns_kst_timestamp(self, executor):
        result = executor._stamp()
        assert "+0900" in result

    def test_format_is_iso(self, executor):
        result = executor._stamp()
        dt = datetime.strptime(result, "%Y-%m-%dT%H:%M:%S%z")
        assert dt.tzinfo is not None

    def test_is_current_time(self, executor):
        before = datetime.now(ex.StepExecutor.TZ).replace(microsecond=0)
        result = executor._stamp()
        after = datetime.now(ex.StepExecutor.TZ).replace(microsecond=0) + timedelta(seconds=1)
        parsed = datetime.strptime(result, "%Y-%m-%dT%H:%M:%S%z")
        assert before <= parsed <= after


# ---------------------------------------------------------------------------
# _read_json / _write_json
# ---------------------------------------------------------------------------

class TestJsonHelpers:
    def test_roundtrip(self, tmp_path):
        data = {"key": "값", "nested": [1, 2, 3]}
        p = tmp_path / "test.json"
        ex.StepExecutor._write_json(p, data)
        loaded = ex.StepExecutor._read_json(p)
        assert loaded == data

    def test_save_ensures_ascii_false(self, tmp_path):
        p = tmp_path / "test.json"
        ex.StepExecutor._write_json(p, {"한글": "테스트"})
        raw = p.read_text()
        assert "한글" in raw
        assert "\\u" not in raw

    def test_save_indented(self, tmp_path):
        p = tmp_path / "test.json"
        ex.StepExecutor._write_json(p, {"a": 1})
        raw = p.read_text()
        assert "\n" in raw

    def test_load_nonexistent_raises(self, tmp_path):
        with pytest.raises(FileNotFoundError):
            ex.StepExecutor._read_json(tmp_path / "nope.json")


# ---------------------------------------------------------------------------
# _build_step_context
# ---------------------------------------------------------------------------

class TestBuildStepContext:
    def test_includes_completed_with_summary(self, phase_dir):
        index = json.loads((phase_dir / "index.json").read_text())
        result = ex.StepExecutor._build_step_context(index)
        assert "Step 0 (setup): 프로젝트 초기화 완료" in result
        assert "Step 1 (core): 핵심 로직 구현" in result

    def test_excludes_pending(self, phase_dir):
        index = json.loads((phase_dir / "index.json").read_text())
        result = ex.StepExecutor._build_step_context(index)
        assert "ui" not in result

    def test_excludes_completed_without_summary(self, phase_dir):
        index = json.loads((phase_dir / "index.json").read_text())
        del index["steps"][0]["summary"]
        result = ex.StepExecutor._build_step_context(index)
        assert "setup" not in result
        assert "core" in result

    def test_empty_when_no_completed(self):
        index = {"steps": [{"step": 0, "name": "a", "status": "pending"}]}
        result = ex.StepExecutor._build_step_context(index)
        assert result == ""

    def test_has_header(self, phase_dir):
        index = json.loads((phase_dir / "index.json").read_text())
        result = ex.StepExecutor._build_step_context(index)
        assert result.startswith("## 이전 Step 산출물")


# ---------------------------------------------------------------------------
# _build_preamble
# ---------------------------------------------------------------------------

class TestBuildPreamble:
    def test_includes_project_name(self, executor):
        result = executor._build_preamble("")
        assert "TestProject" in result

    def test_points_to_agents_md(self, executor):
        result = executor._build_preamble("")
        assert "AGENTS.md" in result

    def test_includes_step_context(self, executor):
        ctx = "## 이전 Step 산출물\n\n- Step 0: done"
        result = executor._build_preamble(ctx)
        assert "이전 Step 산출물" in result

    def test_tells_not_to_commit(self, executor):
        result = executor._build_preamble("")
        assert "커밋하지 마라" in result

    def test_includes_rules(self, executor):
        result = executor._build_preamble("")
        assert "작업 규칙" in result
        assert "AC" in result

    def test_no_retry_section_by_default(self, executor):
        result = executor._build_preamble("")
        assert "이전 시도 실패" not in result

    def test_retry_section_with_prev_error(self, executor):
        result = executor._build_preamble("", prev_error="타입 에러 발생")
        assert "이전 시도 실패" in result
        assert "타입 에러 발생" in result

    def test_includes_max_retries(self, executor):
        result = executor._build_preamble("")
        assert str(ex.StepExecutor.MAX_RETRIES) in result

    def test_index_path_is_repo_relative(self, executor):
        result = executor._build_preamble("")
        assert "phases/0-mvp/index.json" in result
        assert "/phases/" not in result

    def test_no_resume_section_by_default(self, executor):
        result = executor._build_preamble("")
        assert "이전 실행에서 멈췄다" not in result

    def test_resume_section_with_reason(self, executor):
        result = executor._build_preamble("", resume_reason="[blocked] Docker 미실행")
        assert "이전 실행에서 멈췄다" in result
        assert "Docker 미실행" in result
        assert "처음부터 다시 만들지 말고" in result


# ---------------------------------------------------------------------------
# _update_top_index
# ---------------------------------------------------------------------------

class TestUpdateTopIndex:
    def test_completed(self, executor, top_index):
        executor._top_index_file = top_index
        executor._update_top_index("completed")
        data = json.loads(top_index.read_text())
        mvp = next(p for p in data["phases"] if p["dir"] == "0-mvp")
        assert mvp["status"] == "completed"
        assert "completed_at" in mvp

    def test_error(self, executor, top_index):
        executor._top_index_file = top_index
        executor._update_top_index("error")
        data = json.loads(top_index.read_text())
        mvp = next(p for p in data["phases"] if p["dir"] == "0-mvp")
        assert mvp["status"] == "error"
        assert "failed_at" in mvp

    def test_blocked(self, executor, top_index):
        executor._top_index_file = top_index
        executor._update_top_index("blocked")
        data = json.loads(top_index.read_text())
        mvp = next(p for p in data["phases"] if p["dir"] == "0-mvp")
        assert mvp["status"] == "blocked"
        assert "blocked_at" in mvp

    def test_other_phases_unchanged(self, executor, top_index):
        executor._top_index_file = top_index
        executor._update_top_index("completed")
        data = json.loads(top_index.read_text())
        polish = next(p for p in data["phases"] if p["dir"] == "1-polish")
        assert polish["status"] == "pending"

    def test_nonexistent_dir_is_noop(self, executor, top_index):
        executor._top_index_file = top_index
        executor._phase_dir_name = "no-such-dir"
        original = json.loads(top_index.read_text())
        executor._update_top_index("completed")
        after = json.loads(top_index.read_text())
        for p_before, p_after in zip(original["phases"], after["phases"]):
            assert p_before["status"] == p_after["status"]

    def test_no_top_index_file(self, executor, tmp_path):
        executor._top_index_file = tmp_path / "nonexistent.json"
        executor._update_top_index("completed")  # should not raise


# ---------------------------------------------------------------------------
# _checkout_branch (mocked)
# ---------------------------------------------------------------------------

class TestCheckoutBranch:
    def _mock_git(self, executor, responses):
        call_idx = {"i": 0}
        def fake_git(*args):
            idx = call_idx["i"]
            call_idx["i"] += 1
            if idx < len(responses):
                return responses[idx]
            return MagicMock(returncode=0, stdout="", stderr="")
        executor._run_git = fake_git

    def test_already_on_branch(self, executor):
        self._mock_git(executor, [
            MagicMock(returncode=0, stdout="feat-mvp\n", stderr=""),
        ])
        executor._checkout_branch()  # should return without checkout

    def test_branch_exists_checkout(self, executor):
        self._mock_git(executor, [
            MagicMock(returncode=0, stdout="main\n", stderr=""),
            MagicMock(returncode=0, stdout="", stderr=""),
            MagicMock(returncode=0, stdout="", stderr=""),
        ])
        executor._checkout_branch()

    def test_branch_not_exists_create(self, executor):
        self._mock_git(executor, [
            MagicMock(returncode=0, stdout="main\n", stderr=""),
            MagicMock(returncode=1, stdout="", stderr="not found"),
            MagicMock(returncode=0, stdout="", stderr=""),
        ])
        executor._checkout_branch()

    def test_checkout_fails_exits(self, executor):
        self._mock_git(executor, [
            MagicMock(returncode=0, stdout="main\n", stderr=""),
            MagicMock(returncode=1, stdout="", stderr=""),
            MagicMock(returncode=1, stdout="", stderr="dirty tree"),
        ])
        with pytest.raises(SystemExit) as exc_info:
            executor._checkout_branch()
        assert exc_info.value.code == 1

    def test_no_git_exits(self, executor):
        self._mock_git(executor, [
            MagicMock(returncode=1, stdout="", stderr="not a git repo"),
        ])
        with pytest.raises(SystemExit) as exc_info:
            executor._checkout_branch()
        assert exc_info.value.code == 1


# ---------------------------------------------------------------------------
# _commit_step (mocked)
# ---------------------------------------------------------------------------

class TestCommitStep:
    def test_two_phase_commit(self, executor):
        calls = []
        def fake_git(*args):
            calls.append(args)
            if args[:2] == ("diff", "--cached"):
                return MagicMock(returncode=1)
            return MagicMock(returncode=0, stdout="", stderr="")
        executor._run_git = fake_git

        executor._commit_step(2, "ui")

        commit_calls = [c for c in calls if c[0] == "commit"]
        assert len(commit_calls) == 2
        assert "feat(mvp):" in commit_calls[0][2]
        assert "chore(mvp):" in commit_calls[1][2]

    def test_no_code_changes_skips_feat_commit(self, executor):
        call_count = {"diff": 0}
        calls = []
        def fake_git(*args):
            calls.append(args)
            if args[:2] == ("diff", "--cached"):
                call_count["diff"] += 1
                if call_count["diff"] == 1:
                    return MagicMock(returncode=0)
                return MagicMock(returncode=1)
            return MagicMock(returncode=0, stdout="", stderr="")
        executor._run_git = fake_git

        executor._commit_step(2, "ui")

        commit_msgs = [c[2] for c in calls if c[0] == "commit"]
        assert len(commit_msgs) == 1
        assert "chore" in commit_msgs[0]


# ---------------------------------------------------------------------------
# _invoke_codex (mocked)
# ---------------------------------------------------------------------------

class TestInvokeCodex:
    def test_invokes_codex_exec_with_prompt_on_stdin(self, executor):
        mock_result = MagicMock(returncode=0, stdout='{"type": "done"}', stderr="")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result) as mock_run:
            executor._invoke_codex(step, "PREAMBLE\n")

        cmd = mock_run.call_args[0][0]
        assert cmd[:2] == ["codex", "exec"]
        assert "--dangerously-bypass-approvals-and-sandbox" in cmd
        assert "--dangerously-bypass-hook-trust" in cmd  # 없으면 .codex/hooks.json(TDD 가드)이 조용히 빠진다
        assert cmd[-1] == "-"
        prompt = mock_run.call_args[1]["input"]
        assert "PREAMBLE" in prompt
        assert "UI를 구현하세요" in prompt

    def test_saves_output_json(self, executor):
        mock_result = MagicMock(returncode=0, stdout='{"ok": true}', stderr="")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result):
            executor._invoke_codex(step, "preamble")

        output_file = executor._phase_dir / "step2-output.json"
        assert output_file.exists()
        data = json.loads(output_file.read_text())
        assert data["step"] == 2
        assert data["name"] == "ui"
        assert data["exitCode"] == 0

    def test_nonexistent_step_file_exits(self, executor):
        step = {"step": 99, "name": "nonexistent"}
        with pytest.raises(SystemExit) as exc_info:
            executor._invoke_codex(step, "preamble")
        assert exc_info.value.code == 1

    def test_timeout_is_1800(self, executor):
        mock_result = MagicMock(returncode=0, stdout="{}", stderr="")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result) as mock_run:
            executor._invoke_codex(step, "preamble")

        assert mock_run.call_args[1]["timeout"] == 1800

    @pytest.mark.parametrize("partial", [b'{"type": "turn.started"}', '{"type": "turn.started"}', None])
    def test_timeout_is_recorded_not_raised(self, executor, partial):
        """시간 초과는 실패한 시도다 — 하네스가 예외로 죽어 상태 기록 없이 끝나면 안 된다."""
        step = {"step": 2, "name": "ui"}
        timeout = subprocess.TimeoutExpired(cmd="codex", timeout=1800, output=partial)

        with patch("subprocess.run", side_effect=timeout):
            output = executor._invoke_codex(step, "preamble")

        assert output["timedOut"] is True
        saved = json.loads((executor._phase_dir / "step2-output.json").read_text())
        assert saved["timedOut"] is True
        assert saved["stdout"] == ('{"type": "turn.started"}' if partial else "")


# ---------------------------------------------------------------------------
# _review_step (mocked)
# ---------------------------------------------------------------------------

def _claude_json(structured):
    return json.dumps({"type": "result", "is_error": False, "structured_output": structured})


class TestReviewStep:
    APPROVE = {"verdict": "approve", "summary": "좋다", "findings": []}

    def test_invokes_read_only_claude_with_schema(self, executor):
        mock_result = MagicMock(returncode=0, stdout=_claude_json(self.APPROVE), stderr="")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result) as mock_run:
            executor._review_step(step, "abc123")

        cmd = mock_run.call_args[0][0]
        assert cmd[:2] == ["claude", "-p"]
        assert cmd[cmd.index("--permission-mode") + 1] == "dontAsk"
        assert "--json-schema" in cmd
        assert "--dangerously-skip-permissions" not in cmd
        allowed = cmd[cmd.index("--allowedTools") + 1:]
        assert "Read" in allowed
        assert not any(t in ("Edit", "Write", "Bash") for t in allowed)

    def test_prompt_has_base_and_step(self, executor):
        mock_result = MagicMock(returncode=0, stdout=_claude_json(self.APPROVE), stderr="")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result) as mock_run:
            executor._review_step(step, "abc123")

        prompt = mock_run.call_args[1]["input"]
        assert "git diff abc123" in prompt
        assert "UI를 구현하세요" in prompt

    def test_reviewer_runs_backend_and_frontend_from_repo_root(self, executor):
        mock_result = MagicMock(returncode=0, stdout=_claude_json(self.APPROVE), stderr="")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result) as mock_run:
            executor._review_step(step, "abc123")

        cmd = mock_run.call_args[0][0]
        allowed = cmd[cmd.index("--allowedTools") + 1:]
        assert "Bash(./backend/gradlew:*)" in allowed
        assert "Bash(npm --prefix frontend run:*)" in allowed
        assert "Bash(./gradlew:*)" not in allowed
        assert "./backend/gradlew -p backend" in mock_run.call_args[1]["input"]

    def test_returns_structured_output_and_saves(self, executor):
        mock_result = MagicMock(returncode=0, stdout=_claude_json(self.APPROVE), stderr="")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result):
            review = executor._review_step(step, "abc123")

        assert review == self.APPROVE
        saved = json.loads((executor._phase_dir / "step2-review.json").read_text())
        assert saved["review"] == self.APPROVE

    @pytest.mark.parametrize("stdout", [
        "not json",
        json.dumps({"type": "result", "is_error": True}),
        _claude_json({"verdict": "maybe", "summary": "", "findings": []}),
    ])
    def test_returns_none_when_unusable(self, executor, stdout):
        mock_result = MagicMock(returncode=1, stdout=stdout, stderr="boom")
        step = {"step": 2, "name": "ui"}

        with patch("subprocess.run", return_value=mock_result):
            assert executor._review_step(step, "abc123") is None

    def test_timeout_returns_none_not_raised(self, executor):
        step = {"step": 2, "name": "ui"}
        timeout = subprocess.TimeoutExpired(cmd="claude", timeout=1800)

        with patch("subprocess.run", side_effect=timeout):
            assert executor._review_step(step, "abc123") is None

        saved = json.loads((executor._phase_dir / "step2-review.json").read_text())
        assert saved["timedOut"] is True
        assert saved["review"] is None


# ---------------------------------------------------------------------------
# _execute_single_step — Codex 구현 → Claude 리뷰 루프 (mocked)
# ---------------------------------------------------------------------------

class TestExecuteSingleStep:
    REJECT = {"verdict": "request_changes", "summary": "테스트 없음",
              "findings": ["Foo.java:10 — 테스트가 없다 → FooTest 추가"]}
    APPROVE = {"verdict": "approve", "summary": "좋다", "findings": []}

    def _wire(self, executor, reviews):
        """Codex 는 매번 step 을 completed 로 표시하고, 리뷰는 reviews 를 차례로 돌려준다."""
        preambles, commits = [], []

        def fake_codex(step, preamble):
            preambles.append(preamble)
            index = json.loads(executor._index_file.read_text())
            for s in index["steps"]:
                if s["step"] == step["step"]:
                    s["status"] = "completed"
                    s["summary"] = "UI 구현"
            executor._index_file.write_text(json.dumps(index))

        queue = list(reviews)
        executor._invoke_codex = fake_codex
        executor._review_step = lambda step, base: queue.pop(0)
        executor._commit_step = lambda num, name: commits.append(num)
        executor._run_git = lambda *a: MagicMock(returncode=0, stdout="abc123\n", stderr="")
        return preambles, commits

    def _step2(self, executor):
        index = json.loads(executor._index_file.read_text())
        return next(s for s in index["steps"] if s["step"] == 2)

    def test_approved_step_is_committed(self, executor):
        preambles, commits = self._wire(executor, [self.APPROVE])

        assert executor._execute_single_step(self._step2(executor)) is True
        assert commits == [2]
        assert self._step2(executor)["status"] == "completed"
        assert "completed_at" in self._step2(executor)

    def test_rejection_feeds_findings_to_next_codex_attempt(self, executor):
        preambles, commits = self._wire(executor, [self.REJECT, self.APPROVE])

        assert executor._execute_single_step(self._step2(executor)) is True
        assert len(preambles) == 2
        assert "FooTest 추가" in preambles[1]
        assert commits == [2]

    def test_rejected_every_time_is_error_without_commit(self, executor):
        preambles, commits = self._wire(executor, [self.REJECT] * ex.StepExecutor.MAX_RETRIES)

        with pytest.raises(SystemExit) as exc_info:
            executor._execute_single_step(self._step2(executor))

        assert exc_info.value.code == 1
        assert commits == []
        step = self._step2(executor)
        assert step["status"] == "error"
        assert "리뷰" in step["error_message"]

    def test_review_failure_is_error_without_commit(self, executor):
        preambles, commits = self._wire(executor, [None] * ex.StepExecutor.REVIEW_ATTEMPTS)

        with pytest.raises(SystemExit) as exc_info:
            executor._execute_single_step(self._step2(executor))

        assert exc_info.value.code == 1
        assert len(preambles) == 1
        assert commits == []
        assert self._step2(executor)["status"] == "error"

    def test_review_failure_retries_review_only(self, executor):
        """판정을 못 얻으면 리뷰만 다시 한다 — Codex 를 다시 돌리거나 실행 전체를 멈추지 않는다."""
        preambles, commits = self._wire(executor, [None, self.APPROVE])

        assert executor._execute_single_step(self._step2(executor)) is True
        assert len(preambles) == 1
        assert commits == [2]

    def test_prints_codex_elapsed_after_it_is_measured(self, executor, capsys):
        @contextlib.contextmanager
        def fake_progress(label):
            info = types.SimpleNamespace(elapsed=0.0)
            yield info
            info.elapsed = 42.7  # 실제 progress_indicator 처럼 블록이 끝날 때 채운다

        self._wire(executor, [self.APPROVE])
        with patch.object(ex, "progress_indicator", fake_progress):
            executor._execute_single_step(self._step2(executor))

        assert "[42s]" in capsys.readouterr().out

    def test_codex_timeout_is_fed_to_next_attempt(self, executor):
        preambles, commits = self._wire(executor, [self.APPROVE])
        complete = executor._invoke_codex
        attempts = {"n": 0}

        def codex_times_out_first(step, preamble):
            attempts["n"] += 1
            if attempts["n"] == 1:
                preambles.append(preamble)
                return {"timedOut": True}  # status 를 갱신하지 못하고 끊겼다
            return complete(step, preamble)

        executor._invoke_codex = codex_times_out_first

        assert executor._execute_single_step(self._step2(executor)) is True
        assert len(preambles) == 2
        assert f"{ex.StepExecutor.CODEX_TIMEOUT}초 안에 끝나지 않" in preambles[1]

    @pytest.mark.parametrize("field, value", [
        ("blocked_reason", "Docker 데몬에 연결할 수 없다"),
        ("error_message", "[3회 시도 후 실패] 빌드 실패"),
    ])
    def test_resume_reason_is_fed_once_and_cleared(self, executor, field, value):
        """사용자가 status 만 pending 으로 되돌리면 남은 사유가 첫 시도에 전달되고 지워진다."""
        index = json.loads(executor._index_file.read_text())
        step = next(s for s in index["steps"] if s["step"] == 2)
        step[field] = value
        step["blocked_at" if field == "blocked_reason" else "failed_at"] = "2026-10-12T07:00:00+0900"
        executor._index_file.write_text(json.dumps(index))
        preambles, commits = self._wire(executor, [self.APPROVE])

        assert executor._execute_single_step(self._step2(executor)) is True
        assert value in preambles[0]
        assert "이전 실행에서 멈췄다" in preambles[0]
        after = self._step2(executor)
        for gone in ("blocked_reason", "error_message", "blocked_at", "failed_at"):
            assert gone not in after

    def test_review_only_skips_codex_on_first_attempt(self, executor):
        """Codex 는 끝냈지만 리뷰 전에 하네스가 멈춘 step — 리뷰부터 다시 한다."""
        preambles, commits = self._wire(executor, [self.APPROVE])
        index = json.loads(executor._index_file.read_text())
        next(s for s in index["steps"] if s["step"] == 2)["status"] = "completed"
        executor._index_file.write_text(json.dumps(index))

        assert executor._execute_single_step(self._step2(executor), review_only=True) is True
        assert preambles == []
        assert commits == [2]

    def test_review_only_rejection_sends_findings_to_codex(self, executor):
        preambles, commits = self._wire(executor, [self.REJECT, self.APPROVE])
        index = json.loads(executor._index_file.read_text())
        next(s for s in index["steps"] if s["step"] == 2)["status"] = "completed"
        executor._index_file.write_text(json.dumps(index))

        assert executor._execute_single_step(self._step2(executor), review_only=True) is True
        assert len(preambles) == 1
        assert "FooTest 추가" in preambles[0]


# ---------------------------------------------------------------------------
# _execute_all_steps — 다음 step 고르기 · 멈춤 요청 (mocked)
# ---------------------------------------------------------------------------

class TestExecuteAllSteps:
    def _wire(self, executor, tmp_project):
        """한 step 을 실행하면 리뷰 승인까지 끝난 것으로 표시한다."""
        calls = []

        def fake_single(step, review_only=False):
            calls.append((step["step"], review_only))
            index = json.loads(executor._index_file.read_text())
            for s in index["steps"]:
                if s["step"] == step["step"]:
                    s["status"] = "completed"
                    s["completed_at"] = "2026-10-12T08:00:00+0900"
            executor._index_file.write_text(json.dumps(index))
            return True

        executor._execute_single_step = fake_single
        executor._run_git = lambda *a: MagicMock(returncode=0, stdout=".git/harness-stop\n", stderr="")
        (tmp_project / ".git").mkdir(exist_ok=True)
        return calls

    def _add_step3(self, executor):
        index = json.loads(executor._index_file.read_text())
        index["steps"].append({"step": 3, "name": "api", "status": "pending"})
        executor._index_file.write_text(json.dumps(index))

    def test_runs_pending_steps_in_order(self, executor, tmp_project):
        calls = self._wire(executor, tmp_project)
        self._add_step3(executor)

        executor._execute_all_steps()

        assert calls == [(2, False), (3, False)]

    def test_stop_file_halts_before_next_step_and_is_removed(self, executor, tmp_project):
        calls = self._wire(executor, tmp_project)
        stop = tmp_project / ".git" / "harness-stop"
        stop.touch()

        with pytest.raises(SystemExit) as exc_info:
            executor._execute_all_steps()

        assert exc_info.value.code == 0
        assert calls == []
        assert not stop.exists()
        step2 = next(s for s in json.loads(executor._index_file.read_text())["steps"] if s["step"] == 2)
        assert "started_at" not in step2

    def test_unreviewed_completed_step_is_reviewed_before_pending(self, executor, tmp_project):
        """completed 인데 completed_at(리뷰 승인 기록)이 없으면 건너뛰지 않고 리뷰부터 한다."""
        calls = self._wire(executor, tmp_project)
        index = json.loads(executor._index_file.read_text())
        next(s for s in index["steps"] if s["step"] == 2)["status"] = "completed"
        executor._index_file.write_text(json.dumps(index))
        self._add_step3(executor)

        executor._execute_all_steps()

        assert calls == [(2, True), (3, False)]


# ---------------------------------------------------------------------------
# progress_indicator (= 이전 Spinner)
# ---------------------------------------------------------------------------

class TestProgressIndicator:
    def test_context_manager(self):
        import time
        with ex.progress_indicator("test") as pi:
            time.sleep(0.15)
        assert pi.elapsed >= 0.1

    def test_elapsed_increases(self):
        import time
        with ex.progress_indicator("test") as pi:
            time.sleep(0.2)
        assert pi.elapsed > 0


# ---------------------------------------------------------------------------
# main() CLI 파싱 (mocked)
# ---------------------------------------------------------------------------

class TestMainCli:
    def test_no_args_exits(self):
        with patch("sys.argv", ["execute.py"]):
            with pytest.raises(SystemExit) as exc_info:
                ex.main()
            assert exc_info.value.code == 2  # argparse exits with 2

    def test_invalid_phase_dir_exits(self):
        with patch("sys.argv", ["execute.py", "nonexistent"]):
            with patch.object(ex, "ROOT", Path("/tmp/fake_nonexistent")):
                with pytest.raises(SystemExit) as exc_info:
                    ex.main()
                assert exc_info.value.code == 1

    def test_missing_index_exits(self, tmp_project):
        (tmp_project / "phases" / "empty").mkdir()
        with patch("sys.argv", ["execute.py", "empty"]):
            with patch.object(ex, "ROOT", tmp_project):
                with pytest.raises(SystemExit) as exc_info:
                    ex.main()
                assert exc_info.value.code == 1

    @pytest.mark.parametrize("name", ["../outside", "0-mvp/nested"])
    def test_phase_outside_phases_dir_exits(self, tmp_project, phase_dir, name):
        """phases/ 바로 아래 디렉터리가 아니면 index.json 이 있어도 거절한다 — 경로 조작 방지."""
        index = (phase_dir / "index.json").read_text()
        for d in (tmp_project / "outside", phase_dir / "nested"):
            d.mkdir()
            (d / "index.json").write_text(index)
        with patch.object(ex, "ROOT", tmp_project):
            with pytest.raises(SystemExit) as exc_info:
                ex.StepExecutor(name)
            assert exc_info.value.code == 1


# ---------------------------------------------------------------------------
# _check_blockers (= 이전 main() error/blocked 체크)
# ---------------------------------------------------------------------------

class TestCheckBlockers:
    def _make_executor_with_steps(self, tmp_project, steps):
        d = tmp_project / "phases" / "test-phase"
        d.mkdir(exist_ok=True)
        index = {"project": "T", "phase": "test", "steps": steps}
        (d / "index.json").write_text(json.dumps(index))

        with patch.object(ex, "ROOT", tmp_project):
            inst = ex.StepExecutor.__new__(ex.StepExecutor)
        inst._root = str(tmp_project)
        inst._phases_dir = tmp_project / "phases"
        inst._phase_dir = d
        inst._phase_dir_name = "test-phase"
        inst._index_file = d / "index.json"
        inst._top_index_file = tmp_project / "phases" / "index.json"
        inst._phase_name = "test"
        inst._total = len(steps)
        return inst

    def test_error_step_exits_1(self, tmp_project):
        steps = [
            {"step": 0, "name": "ok", "status": "completed"},
            {"step": 1, "name": "bad", "status": "error", "error_message": "fail"},
        ]
        inst = self._make_executor_with_steps(tmp_project, steps)
        with pytest.raises(SystemExit) as exc_info:
            inst._check_blockers()
        assert exc_info.value.code == 1

    def test_blocked_step_exits_2(self, tmp_project):
        steps = [
            {"step": 0, "name": "ok", "status": "completed"},
            {"step": 1, "name": "stuck", "status": "blocked", "blocked_reason": "API key"},
        ]
        inst = self._make_executor_with_steps(tmp_project, steps)
        with pytest.raises(SystemExit) as exc_info:
            inst._check_blockers()
        assert exc_info.value.code == 2
