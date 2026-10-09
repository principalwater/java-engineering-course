const {execFileSync} = require('node:child_process');
const {readFileSync} = require('node:fs');

function git(...args) {
  return execFileSync('git', args, {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
}

function sha(value) {
  if (!/^[a-f0-9]{40}$/.test(value ?? '')) throw new Error('Некорректный SHA в событии GitHub');
  return value;
}

try {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  let commits;
  if (process.env.GITHUB_EVENT_NAME === 'pull_request') {
    const base = sha(event.pull_request?.base?.sha);
    const head = sha(event.pull_request?.head?.sha);
    commits = git('rev-list', '--reverse', base + '..' + head).trim();
  } else if (process.env.GITHUB_EVENT_NAME === 'push') {
    const before = sha(event.before);
    const after = sha(event.after);
    if (event.deleted) {
      commits = '';
    } else {
      const base = /^0+$/.test(before) ? git('merge-base', 'origin/main', after).trim() : before;
      commits = git('rev-list', '--reverse', base + '..' + after).trim();
    }
  } else if (process.env.GITHUB_EVENT_NAME === 'workflow_dispatch') {
    commits = git('rev-parse', 'HEAD').trim();
  } else {
    throw new Error('Неподдерживаемое событие проверки коммитов');
  }
  const revisions = commits ? commits.split('\n') : [];
  for (const revision of revisions) {
    // Удаляем только терминальный перевод строки Git, сохраняя тело и трейлеры для проверки.
    const message = git('show', '--no-patch', '--format=format:%B', revision).replace(/\n$/, '');
    if (!/^SPRINT-\d{2}: [a-z][\x20-\x7e]*$/.test(message)) {
      throw new Error('Коммит ' + revision.slice(0, 12) +
        ': нужна одна английская строка SPRINT-NN: description, без тела и трейлеров');
    }
  }
  console.log('Проверено коммитов: ' + revisions.length);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
