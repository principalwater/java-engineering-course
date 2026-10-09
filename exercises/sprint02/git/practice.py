#!/usr/bin/env python3
"""Воспроизводит Git-упражнения в собственных каталогах target; готовит ветку для squash в IDE."""

import os
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path


def git(repo, *args, check=True, input=None, env=None):
    return subprocess.run(["git", *args], cwd=repo, text=True, input=input,
                          capture_output=True, check=check, env=env)


def initialize(base, name):
    repo = base / name
    repo.mkdir()
    git(repo, "init", "-b", "main")
    return repo


def main():
    base = Path(__file__).resolve().parent / "target"
    if base.exists():
        raise SystemExit("Каталог target уже существует. Сохраните результаты предыдущего упражнения перед новым запуском.")
    base.mkdir()

    repo = initialize(base, "partial-staging")
    original = [f"row {i:02}" for i in range(1, 31)]
    file = repo / "someFileName"
    file.write_text("\n".join(original) + "\n", encoding="utf-8")
    git(repo, "add", "someFileName")
    git(repo, "commit", "-m", "SPRINT-02: seed partial staging exercise")
    changed = original.copy()
    changed[0], changed[-1] = "changed header", "changed footer"
    file.write_text("\n".join(changed) + "\n", encoding="utf-8")
    git(repo, "add", "-p", "someFileName", input="y\nn\n")
    indexed = git(repo, "show", ":someFileName").stdout.splitlines()
    assert indexed[0] == "changed header" and indexed[-1] == "row 30"
    git(repo, "commit", "-m", "SPRINT-02: commit only the first hunk")
    assert git(repo, "diff", "--name-only").stdout.strip() == "someFileName"
    print("Индексирование: первое изменение закоммичено, последнее осталось в рабочем файле.")

    repo = initialize(base, "stash-practice")
    file = repo / "someFileName"
    original = "\n".join(f"row {i:02}" for i in range(1, 11)) + "\n"
    file.write_text(original, encoding="utf-8")
    git(repo, "add", "someFileName")
    git(repo, "commit", "-m", "SPRINT-02: seed stash exercise")
    changed = original.replace("row 01", "edited row 01")
    file.write_text(changed, encoding="utf-8")
    git(repo, "stash", "push", "-m", "МоиИзменения")
    assert file.read_text(encoding="utf-8") == original
    assert "МоиИзменения" in git(repo, "stash", "list").stdout
    git(repo, "stash", "apply", "stash@{0}")
    assert file.read_text(encoding="utf-8") == changed
    git(repo, "stash", "drop", "stash@{0}")
    assert not git(repo, "stash", "list").stdout.strip()
    print("Stash: изменения сохранены, просмотрены, восстановлены; запись удалена.")

    repo = initialize(base, "bisect-practice")
    file = repo / "answer.txt"
    file.write_text("42\n", encoding="utf-8")
    git(repo, "add", "answer.txt")
    git(repo, "commit", "-m", "SPRINT-02: seed a correct answer")
    good = git(repo, "rev-parse", "HEAD").stdout.strip()
    (repo / "note-a.txt").write_text("first note\n", encoding="utf-8")
    git(repo, "add", "note-a.txt")
    git(repo, "commit", "-m", "SPRINT-02: add an unrelated note")
    file.write_text("41\n", encoding="utf-8")
    git(repo, "add", "answer.txt")
    git(repo, "commit", "-m", "SPRINT-02: introduce an answer regression")
    introduced = git(repo, "rev-parse", "HEAD").stdout.strip()
    for name in ("b", "c"):
        (repo / f"note-{name}.txt").write_text(f"note {name}\n", encoding="utf-8")
        git(repo, "add", f"note-{name}.txt")
        git(repo, "commit", "-m", f"SPRINT-02: add note {name} after the regression")
    head = git(repo, "rev-parse", "HEAD").stdout.strip()
    assert git(repo, "rev-list", "--count", "HEAD").stdout.strip() == "5"
    git(repo, "bisect", "start", head, good)
    # Текст вывода bisect меняется между версиями Git; результат проверяем по ref, а не по фразе.
    git(repo, "bisect", "run", sys.executable, "-c",
        "from pathlib import Path; import sys; "
        "sys.exit(0 if Path('answer.txt').read_text().strip() == '42' else 1)")
    assert git(repo, "rev-parse", "refs/bisect/bad").stdout.strip() == introduced
    git(repo, "bisect", "reset")
    assert git(repo, "rev-parse", "HEAD").stdout.strip() == head
    print("Bisect: первый плохой коммит найден среди пяти; исходная ветка восстановлена.")

    repo = initialize(base, "history-practice")
    (repo / ".gitignore").write_text(".idea/\n", encoding="utf-8")
    file = repo / "someFileName"
    for count in range(1, 4):
        file.write_text("\n".join(f"line {i}" for i in range(1, count + 1)) + "\n", encoding="utf-8")
        git(repo, "add", "someFileName", ".gitignore")
        git(repo, "commit", "-m", f"SPRINT-02: add sample line {count}")
    assert git(repo, "rev-list", "--count", "HEAD").stdout.strip() == "3"
    git(repo, "branch", "archive/pre-root-squash")
    invalid = git(repo, "rebase", "-i", "HEAD~3", check=False)
    assert invalid.returncode != 0 and "invalid upstream" in invalid.stderr
    before = git(repo, "rev-parse", "HEAD^{tree}").stdout.strip()
    with tempfile.TemporaryDirectory(prefix="git-practice-editor-") as temporary:
        sequence = Path(temporary) / "sequence.py"
        message = Path(temporary) / "message.py"
        sequence.write_text(
            "from pathlib import Path\nimport sys\n"
            "p=Path(sys.argv[1]); lines=p.read_text().splitlines(); seen=False; result=[]\n"
            "for line in lines:\n"
            " if line.startswith('pick '):\n"
            "  if seen: line='squash '+line[5:]\n"
            "  seen=True\n result.append(line)\n"
            "p.write_text('\\n'.join(result)+'\\n')\n", encoding="utf-8")
        message.write_text(
            "from pathlib import Path\nimport sys\n"
            "Path(sys.argv[1]).write_text('SPRINT-02: combine the sample content\\n')\n", encoding="utf-8")
        env = os.environ.copy()
        env["GIT_SEQUENCE_EDITOR"] = shlex.quote(sys.executable) + " " + shlex.quote(str(sequence))
        env["GIT_EDITOR"] = shlex.quote(sys.executable) + " " + shlex.quote(str(message))
        git(repo, "rebase", "-i", "--root", env=env)
    assert git(repo, "rev-list", "--count", "HEAD").stdout.strip() == "1"
    assert git(repo, "rev-parse", "HEAD^{tree}").stdout.strip() == before
    assert len(git(repo, "log", "-1", "--format=%B").stdout.strip().splitlines()) == 1
    git(repo, "switch", "-c", "feature/sprint02-ide-squash")
    for index in range(1, 4):
        (repo / f"ide-{index}.txt").write_text(f"IDE sample {index}\n", encoding="utf-8")
        git(repo, "add", f"ide-{index}.txt")
        git(repo, "commit", "-m", f"SPRINT-02: add IDE sample {index}")
    git(repo, "branch", "archive/pre-ide-squash")
    assert git(repo, "rev-list", "--count", "HEAD").stdout.strip() == "4"
    print("Rebase: три корневых коммита объединены без изменения файлов.")
    print("Для IDE подготовлена ветка с тремя новыми коммитами:", repo)


if __name__ == "__main__":
    main()
