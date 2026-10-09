const assert = require('node:assert/strict');
const {execFileSync, spawnSync} = require('node:child_process');
const {mkdtempSync, writeFileSync, rmSync} = require('node:fs');
const {tmpdir} = require('node:os');
const path = require('node:path');
const test = require('node:test');

const SCRIPT = path.resolve(__dirname, '../scripts/check-commits.cjs');

// Проверяется CLI и настоящая Git-история: общий legacy-коммит не входит в новый диапазон.
test('проверка сообщений учитывает диапазон события и отказывает телу, трейлерам и неверному SHA', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'course-commit-style-'));
  const git = (...args) => execFileSync('git', args, {cwd: directory, encoding: 'utf8'}).trim();
  try {
    git('init', '--initial-branch=main');
    git('config', 'user.name', 'Commit fixture');
    git('config', 'user.email', 'fixture@example.invalid');
    git('config', 'commit.gpgsign', 'false');
    git('config', 'core.hooksPath', '/dev/null');
    const messageFile = path.join(directory, 'message.txt');
    const commit = message => {
      writeFileSync(messageFile, message);
      git('commit', '--allow-empty', '--cleanup=verbatim', '-F', messageFile);
      return git('rev-parse', 'HEAD');
    };
    const legacy = commit('Legacy title\n');
    git('update-ref', 'refs/remotes/origin/main', legacy);
    const valid = commit('SPRINT-03: add source\n');
    const invoke = (eventName, event) => {
      const eventFile = path.join(directory, 'event.json');
      writeFileSync(eventFile, JSON.stringify(event));
      return spawnSync(process.execPath, [SCRIPT], {
        cwd: directory, encoding: 'utf8',
        env: {...process.env, GITHUB_EVENT_NAME: eventName, GITHUB_EVENT_PATH: eventFile},
      });
    };
    for (const [name, event] of [
      ['pull_request', {pull_request: {base: {sha: legacy}, head: {sha: valid}}}],
      ['push', {before: legacy, after: valid}],
      ['push', {before: '0'.repeat(40), after: valid, created: true}],
      ['workflow_dispatch', {}],
    ]) {
      const result = invoke(name, event);
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /Проверено коммитов: 1/);
    }
    let previous = valid;
    for (const message of [
      'No sprint prefix\n',
      'SPRINT-03: Uppercase description\n',
      'SPRINT-03: описание\n',
      'SPRINT-03: add source\n\nExtra body\n',
      'SPRINT-03: add source\n\nCo-Authored-By: Fixture <fixture@example.invalid>\n',
    ]) {
      const current = commit(message);
      const result = invoke('push', {before: previous, after: current});
      assert.equal(result.status, 1);
      assert.match(result.stderr, /нужна одна английская строка.*без тела и трейлеров/);
      previous = current;
    }
    const invalidSha = invoke('pull_request', {
      pull_request: {base: {sha: legacy}, head: {sha: '--all'}},
    });
    assert.equal(invalidSha.status, 1);
    assert.match(invalidSha.stderr, /Некорректный SHA/);
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
});
