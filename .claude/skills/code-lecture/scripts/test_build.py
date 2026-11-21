"""build.py 검사 테스트 — 더 알아두기 · 배경 장 · 퀴즈.

발췌 없는(code: null) 작은 강의로 build.py 를 실제로 돌려 종료 코드와 메시지를 본다.
실행: python3 -m pytest .claude/skills/code-lecture/scripts/test_build.py
"""

import copy
import json
import subprocess
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
BUILD = HERE / "build.py"
REPO = HERE.parents[3]

BASE = {
    "title": "시험 강의",
    "foot": "시험",
    "chapters": [
        {
            "title": "첫 장",
            "nodes": [{"id": "a", "x": 10, "y": 10, "w": 120, "h": 50, "l": "가"}],
            "edges": [],
            "beats": [
                {"say": "첫 단계", "detail": ["설명"], "code": None},
                {"say": "둘째 단계", "detail": ["설명"],
                 "know": {"title": "행 잠금", "body": ["문단", ["목록 1", "목록 2"]]}},
            ],
        },
        {
            "title": "배경",
            "kind": "background",
            "nodes": [],
            "edges": [],
            "beats": [{"say": "배경 단계", "detail": ["설명"], "code": None}],
        },
    ],
    "quiz": [
        {"q": "고르기", "choices": ["가", "나", "다", "라"], "answer": 2, "why": ["해설"], "see": [1, 2]},
        {"q": "고르기 2", "choices": ["가", "나", "다"], "answer": 1, "why": ["해설"], "see": [2, 1]},
        {"q": "쓰기", "model": ["모범 답"], "points": ["기준"], "see": [1, 1]},
    ],
}


def run(lecture, tmp_path):
    src, out = tmp_path / "lecture.json", tmp_path / "lecture.html"
    src.write_text(json.dumps(lecture, ensure_ascii=False), encoding="utf-8")
    done = subprocess.run([sys.executable, "-I", str(BUILD), str(src), str(out), "--repo", str(REPO)],
                          capture_output=True, text=True)
    return done, out


DELETE = object()


def changed(**edits):
    """BASE 사본에 경로별 수정을 적용한다 — 키는 'quiz.0.answer' 처럼 점으로 잇는다. 값이 DELETE 면 지운다."""
    lecture = copy.deepcopy(BASE)
    for path, value in edits.items():
        *head, last = path.split(".")
        target = lecture
        for part in head:
            target = target[int(part)] if part.isdigit() else target[part]
        key = int(last) if last.isdigit() else last
        if value is DELETE:
            del target[key]
        else:
            target[key] = value
    return lecture


def test_valid_lecture_builds_with_quiz_and_knowledge(tmp_path):
    # given
    lecture = BASE

    # when
    done, out = run(lecture, tmp_path)

    # then
    assert done.returncode == 0, done.stdout + done.stderr
    assert "퀴즈 3문항(객관식 2 · 서술형 1)" in done.stdout
    html = out.read_text(encoding="utf-8")
    assert '"know"' in html and '"quiz"' in html and '"background"' in html


def test_example_lecture_builds(tmp_path):
    # given
    example = HERE.parent / "references" / "example.lecture.json"
    out = tmp_path / "example.html"

    # when
    done = subprocess.run([sys.executable, "-I", str(BUILD), str(example), str(out), "--repo", str(REPO)],
                          capture_output=True, text=True)

    # then
    assert done.returncode == 0, done.stdout + done.stderr
    assert "오류" not in done.stdout


@pytest.mark.parametrize("edits, message", [
    ({"quiz": DELETE}, "quiz 가 비었다"),
    ({"quiz": []}, "quiz 가 비었다"),
    ({"quiz.0.q": ""}, "퀴즈 1: q 가 없다"),
    ({"quiz.0.answer": 5}, "퀴즈 1: answer 5 는 choices 1~4 밖이다"),
    ({"quiz.0.answer": 0}, "퀴즈 1: answer 0 는 choices 1~4 밖이다"),
    ({"quiz.0.choices": ["가", "나"]}, "퀴즈 1: choices 는 3~5개다"),
    ({"quiz.0.why": DELETE}, "퀴즈 1: why 가 없다"),
    ({"quiz.2.model": DELETE}, "퀴즈 3: choices(객관식) 나 model(서술형) 중 하나가 있어야 한다"),
    ({"quiz.0.model": ["모범 답"]}, "퀴즈 1: choices 와 model 을 함께 쓰지 않는다"),
    ({"quiz.0.see": [3, 1]}, "퀴즈 1: see [3, 1] 은 없는 단계다"),
    ({"quiz.0.see": [1, 3]}, "퀴즈 1: see [1, 3] 은 없는 단계다"),
    ({"quiz.0.see": DELETE}, "퀴즈 1: see 가 없다"),
    ({"chapters.0.beats.1.know": {"title": "제목"}}, "1장 '첫 장' 2단계: know 에 title 과 body 가 있어야 한다"),
    ({"chapters.0.beats.1.know": "글"}, "1장 '첫 장' 2단계: know 에 title 과 body 가 있어야 한다"),
    ({"chapters.1.kind": "extra"}, "2장 '배경': kind 'extra' 는 없는 종류다"),
])
def test_invalid_lecture_fails(tmp_path, edits, message):
    # given
    lecture = changed(**edits)

    # when
    done, out = run(lecture, tmp_path)

    # then
    assert done.returncode == 1
    assert message in done.stdout
    assert not out.exists()


def test_warns_when_quiz_has_only_one_kind(tmp_path):
    # given
    lecture = changed(**{"quiz": BASE["quiz"][:2]})

    # when
    done, _ = run(lecture, tmp_path)

    # then
    assert done.returncode == 0
    assert "경고: 퀴즈에 서술형이 없다" in done.stdout


def test_warns_when_answers_sit_in_one_position(tmp_path):
    # given
    same = {"q": "고르기", "choices": ["가", "나", "다"], "answer": 2, "why": ["해설"], "see": [1, 1]}
    lecture = changed(**{"quiz": [same, same, same, BASE["quiz"][2]]})

    # when
    done, _ = run(lecture, tmp_path)

    # then
    assert done.returncode == 0
    assert "경고: 객관식 정답이 모두 2번이다" in done.stdout
