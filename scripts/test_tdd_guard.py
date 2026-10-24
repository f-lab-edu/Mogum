"""
tdd_guard.py 테스트 — Claude(Write/Edit) · Codex(apply_patch) 훅 입력 모두를 다룬다.
"""

import io
import json
import subprocess
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import tdd_guard as tg


# ---------------------------------------------------------------------------
# Fixtures · helpers
# ---------------------------------------------------------------------------

@pytest.fixture
def repo(tmp_path):
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    return tmp_path


def put(root: Path, rel: str, content: str = "") -> Path:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)
    return p


def claude_write(root, rel, content="class Foo {}", sid="s1"):
    return {"session_id": sid, "cwd": str(root), "hook_event_name": "PreToolUse",
            "tool_name": "Write", "tool_input": {"file_path": str(root / rel), "content": content}}


def claude_edit(root, rel, sid="s1"):
    return {"session_id": sid, "cwd": str(root), "hook_event_name": "PreToolUse",
            "tool_name": "Edit", "tool_input": {"file_path": str(root / rel), "old_string": "a", "new_string": "b"}}


def codex_patch(root, body, sid="s1"):
    return {"session_id": sid, "cwd": str(root), "hook_event_name": "PreToolUse",
            "tool_name": "apply_patch", "tool_input": {"command": f"*** Begin Patch\n{body}\n*** End Patch"}}


def stop_payload(root, sid="s1", active=False):
    return {"session_id": sid, "cwd": str(root), "hook_event_name": "Stop", "stop_hook_active": active}


FOO = "src/main/java/com/mogum/core/Foo.java"
FOO_TEST = "src/test/java/com/mogum/core/FooTest.java"
CARD = "frontend/src/features/review/ReviewCard.tsx"


# ---------------------------------------------------------------------------
# 분류
# ---------------------------------------------------------------------------

class TestClassify:
    @pytest.mark.parametrize("rel", [FOO, CARD, "frontend/src/api/client.ts"])
    def test_production(self, rel):
        assert tg.is_production(rel)
        assert not tg.is_test(rel)

    @pytest.mark.parametrize("rel", [
        FOO_TEST,
        "frontend/src/features/review/ReviewCard.test.tsx",
        "frontend/src/features/review/__tests__/ReviewCard.test.tsx",
        "frontend/src/api/client.spec.ts",
    ])
    def test_test(self, rel):
        assert tg.is_test(rel)
        assert not tg.is_production(rel)

    @pytest.mark.parametrize("rel", [
        "docs/02-domain-data.md",
        "phases/0-mvp/step0.md",
        "src/main/resources/db/migration/V1__init.sql",
        "frontend/src/index.css",
        "frontend/src/test/setup.ts",
        "scripts/execute.py",
    ])
    def test_neither(self, rel):
        assert not tg.is_production(rel)
        assert not tg.is_test(rel)


# ---------------------------------------------------------------------------
# 예외 — 동작이 없는 파일
# ---------------------------------------------------------------------------

class TestExempt:
    @pytest.mark.parametrize("content", [
        "public interface Foo { void run(); }",
        "public record Foo(long id, String name) {}",
        "public enum Foo { A, B }",
        "public @interface Foo {}",
        "public class Foo extends RuntimeException { }",
        "public final class Foo extends com.mogum.core.DomainException {}",
        "@Entity\n@Table(name = \"foo\")\npublic class Foo { @Id Long id; }",
        "@Embeddable\npublic class Foo {}",
    ])
    def test_java_types_without_behavior(self, repo, content):
        assert tg.is_exempt(repo, FOO, content)

    def test_plain_class_not_exempt(self, repo):
        assert not tg.is_exempt(repo, FOO, "public class Foo { int run() { return 1; } }")

    @pytest.mark.parametrize("rel", [
        "src/main/java/com/mogum/core/package-info.java",
        "src/main/java/com/mogum/MogumApplication.java",
        "frontend/src/main.tsx",
        "frontend/src/vite-env.d.ts",
        "frontend/src/api/types.ts",
        "frontend/src/api/review.types.ts",
    ])
    def test_by_name(self, repo, rel):
        assert tg.is_exempt(repo, rel, "anything")

    def test_reads_disk_when_content_unknown(self, repo):
        put(repo, FOO, "public record Foo(long id) {}")
        assert tg.is_exempt(repo, FOO, None)


# ---------------------------------------------------------------------------
# apply_patch 파싱
# ---------------------------------------------------------------------------

class TestParsePatch:
    def test_add_update_delete_move(self):
        text = (
            "*** Begin Patch\n"
            "*** Add File: a/New.java\n+public record New() {}\n"
            "*** Update File: a/Old.java\n@@\n-x\n+y\n"
            "*** Delete File: a/Gone.java\n"
            "*** Update File: a/From.java\n*** Move to: a/To.java\n@@\n-x\n+y\n"
            "*** End Patch"
        )
        assert tg.parse_patch(text) == [
            ("a/New.java", "public record New() {}\n"),
            ("a/Old.java", None),
            ("a/To.java", None),
        ]


# ---------------------------------------------------------------------------
# PreToolUse — 짝 테스트가 먼저 있어야 구현 파일을 쓴다
# ---------------------------------------------------------------------------

class TestPre:
    def test_claude_write_without_test_is_blocked(self, repo):
        msg = tg.pre(claude_write(repo, FOO), repo)
        assert msg is not None
        assert FOO in msg
        assert FOO_TEST in msg

    @pytest.mark.parametrize("test_rel", [
        FOO_TEST,
        "src/test/java/com/mogum/core/FooConcurrencyTest.java",
        "src/test/java/com/mogum/core/FooIT.java",
    ])
    def test_claude_write_with_existing_test_is_allowed(self, repo, test_rel):
        put(repo, test_rel)
        assert tg.pre(claude_write(repo, FOO), repo) is None

    def test_test_in_other_package_does_not_count(self, repo):
        put(repo, "src/test/java/com/mogum/api/FooTest.java")
        assert tg.pre(claude_write(repo, FOO), repo) is not None

    def test_claude_edit_existing_without_test_is_blocked(self, repo):
        put(repo, FOO, "public class Foo { int run() { return 1; } }")
        assert tg.pre(claude_edit(repo, FOO), repo) is not None

    def test_codex_patch_with_test_and_impl_together_is_allowed(self, repo):
        body = (f"*** Add File: {FOO_TEST}\n+class FooTest {{}}\n"
                f"*** Add File: {FOO}\n+public class Foo {{ int run() {{ return 1; }} }}")
        assert tg.pre(codex_patch(repo, body), repo) is None

    def test_codex_patch_impl_only_is_blocked(self, repo):
        put(repo, FOO, "public class Foo { int run() { return 1; } }")
        msg = tg.pre(codex_patch(repo, f"*** Update File: {FOO}\n@@\n-1\n+2"), repo)
        assert msg is not None
        assert FOO_TEST in msg

    def test_codex_patch_command_as_list(self, repo):
        payload = codex_patch(repo, f"*** Add File: {FOO}\n+public class Foo {{}}")
        payload["tool_input"]["command"] = ["apply_patch", payload["tool_input"]["command"]]
        assert tg.pre(payload, repo) is not None

    def test_codex_add_record_is_allowed(self, repo):
        body = f"*** Add File: {FOO}\n+public record Foo(long id) {{}}"
        assert tg.pre(codex_patch(repo, body), repo) is None

    def test_frontend_without_test_is_blocked(self, repo):
        msg = tg.pre(claude_write(repo, CARD, "export function ReviewCard() {}"), repo)
        assert msg is not None
        assert "frontend/src/features/review/ReviewCard.test.tsx" in msg

    @pytest.mark.parametrize("test_rel", [
        "frontend/src/features/review/ReviewCard.test.tsx",
        "frontend/src/features/review/__tests__/ReviewCard.test.tsx",
    ])
    def test_frontend_with_test_is_allowed(self, repo, test_rel):
        put(repo, test_rel)
        assert tg.pre(claude_write(repo, CARD, "export function ReviewCard() {}"), repo) is None

    @pytest.mark.parametrize("rel", ["docs/05-api.md", "phases/0-mvp/step0.md", "scripts/execute.py"])
    def test_non_production_is_allowed(self, repo, rel):
        assert tg.pre(claude_write(repo, rel, "x"), repo) is None

    def test_other_tools_are_allowed(self, repo):
        payload = {"session_id": "s1", "cwd": str(repo), "tool_name": "Bash",
                   "tool_input": {"command": "ls"}}
        assert tg.pre(payload, repo) is None

    def test_path_outside_repo_is_allowed(self, repo, tmp_path_factory):
        other = tmp_path_factory.mktemp("other")
        payload = claude_write(other, FOO)
        payload["cwd"] = str(repo)
        assert tg.pre(payload, repo) is None


# ---------------------------------------------------------------------------
# Stop — 이번 세션이 구현 파일을 바꿨으면 테스트 변경도 있어야 끝낸다
# ---------------------------------------------------------------------------

class TestStop:
    def _write_via_hook(self, repo, rel, content, sid="s1"):
        """PreToolUse 를 통과시킨 뒤 도구가 파일을 쓴 상황을 만든다."""
        assert tg.pre(claude_write(repo, rel, content, sid), repo) is None
        put(repo, rel, content)

    def test_prod_without_test_change_is_blocked(self, repo):
        put(repo, FOO_TEST)
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init"],
                       cwd=repo, check=True)
        self._write_via_hook(repo, FOO, "public class Foo { int run() { return 1; } }")

        msg = tg.stop(stop_payload(repo), repo)
        assert msg is not None
        assert FOO in msg

    def test_session_touched_test_is_allowed(self, repo):
        self._write_via_hook(repo, FOO_TEST, "class FooTest {}")
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"],
                       cwd=repo, check=True)
        self._write_via_hook(repo, FOO, "public class Foo { int run() { return 1; } }")
        assert tg.stop(stop_payload(repo), repo) is None

    def test_uncommitted_test_in_worktree_is_allowed(self, repo):
        put(repo, FOO_TEST)  # 이전 시도가 남긴 테스트 (untracked)
        self._write_via_hook(repo, FOO, "public class Foo { int run() { return 1; } }")
        assert tg.stop(stop_payload(repo), repo) is None

    def test_no_production_change_is_allowed(self, repo):
        self._write_via_hook(repo, "docs/05-api.md", "x")
        assert tg.stop(stop_payload(repo), repo) is None

    def test_unknown_session_is_allowed(self, repo):
        assert tg.stop(stop_payload(repo, sid="nobody"), repo) is None

    def test_second_stop_is_allowed(self, repo):
        put(repo, FOO_TEST)
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init"],
                       cwd=repo, check=True)
        self._write_via_hook(repo, FOO, "public class Foo { int run() { return 1; } }")
        assert tg.stop(stop_payload(repo, active=True), repo) is None

    def test_exempt_production_is_allowed(self, repo):
        self._write_via_hook(repo, FOO, "public record Foo(long id) {}")
        assert tg.stop(stop_payload(repo), repo) is None

    def test_sessions_are_isolated(self, repo):
        put(repo, FOO_TEST)
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init"],
                       cwd=repo, check=True)
        self._write_via_hook(repo, FOO, "public class Foo { int run() { return 1; } }", sid="codex-1")
        assert tg.stop(stop_payload(repo, sid="claude-1"), repo) is None

    def _commit(self, repo):
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"],
                       cwd=repo, check=True)

    def test_committed_prod_is_allowed(self, repo):
        put(repo, FOO_TEST)
        self._commit(repo)
        self._write_via_hook(repo, FOO, "public class Foo { int run() { return 1; } }")
        self._commit(repo)
        assert tg.stop(stop_payload(repo), repo) is None

    def test_acknowledged_block_does_not_repeat_next_turn(self, repo):
        bar, bar_test = FOO.replace("Foo", "Bar"), FOO_TEST.replace("Foo", "Bar")
        put(repo, FOO_TEST)
        put(repo, bar_test)
        self._commit(repo)
        self._write_via_hook(repo, FOO, "public class Foo { int run() { return 1; } }")
        assert tg.stop(stop_payload(repo), repo) is not None
        assert tg.stop(stop_payload(repo, active=True), repo) is None  # 근거를 적고 다시 끝냄

        assert tg.stop(stop_payload(repo), repo) is None  # 다음 턴 — 같은 변경으로 다시 막지 않는다

        self._write_via_hook(repo, bar, "public class Bar { int run() { return 1; } }")
        msg = tg.stop(stop_payload(repo), repo)  # 새 구현 변경은 다시 검사한다
        assert msg is not None
        assert bar in msg
        assert FOO not in msg


# ---------------------------------------------------------------------------
# main — exit 2 + stderr 로 막는다 (Claude · Codex 공통 규약)
# ---------------------------------------------------------------------------

class TestMain:
    def _run(self, repo, mode, payload, capsys):
        with patch.object(tg, "ROOT", repo), patch("sys.stdin", io.StringIO(json.dumps(payload))):
            code = tg.main(["tdd_guard.py", mode])
        return code, capsys.readouterr().err

    def test_block_exits_2_with_reason(self, repo, capsys):
        code, err = self._run(repo, "pre", claude_write(repo, FOO), capsys)
        assert code == 2
        assert FOO_TEST in err

    def test_allow_exits_0(self, repo, capsys):
        code, err = self._run(repo, "pre", claude_write(repo, "docs/x.md", "x"), capsys)
        assert code == 0
        assert err == ""

    def test_stop_mode(self, repo, capsys):
        code, _ = self._run(repo, "stop", stop_payload(repo), capsys)
        assert code == 0
