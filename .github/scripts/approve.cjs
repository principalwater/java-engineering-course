const OWNER = 'principalwater';
const REVIEW_BOT = 'elementary-flow-bot';
const ACTIONS_APP_ID = 15368;
const REQUIRED_JOBS = new Map([
  ['projects', null],
  ['exercises', 'exercises/sprint01/junit5/pom.xml'],
  ['sprint03', 'projects/sprint03-spring-blog/pom.xml'],
]);

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

module.exports = async function approveAndMerge({github, bot, context}) {
  const {issue, comment} = context.payload;
  requireCondition(context.actor === OWNER && comment?.user?.login === OWNER &&
    comment.body === '/approve' && issue?.pull_request && context.repo.owner === OWNER,
  'Команда /approve доступна только владельцу в комментарии к PR');
  const repository = context.repo;
  const pullRequest = {...repository, pull_number: issue.number};
  const identity = await bot.rest.users.getAuthenticated();
  requireCondition(identity.data.login === REVIEW_BOT,
    'Токен согласования должен принадлежать elementary-flow-bot');

  async function inspect(expectedSha) {
    const {data: pull} = await github.rest.pulls.get(pullRequest);
    requireCondition(pull.state === 'open' && !pull.draft && pull.base.ref === 'main' &&
      pull.user.login === OWNER && pull.head.ref !== 'main' &&
      pull.head.repo?.full_name === repository.owner + '/' + repository.repo,
    'Нужен открытый PR владельца из этого репозитория в main, без draft');
    requireCondition(/^SPRINT-\d{2}: [a-z][\x20-\x7e]*$/.test(pull.title),
      'Заголовок PR должен быть одной английской строкой SPRINT-NN: description');
    requireCondition(pull.mergeable === true, 'Состояние слияния не определено или есть конфликты');
    const sha = pull.head.sha;
    requireCondition(!expectedSha || sha === expectedSha, 'Head PR изменился; повторите /approve');
    const {data: comparison} = await github.rest.repos.compareCommitsWithBasehead({
      ...repository, basehead: 'main...' + sha,
    });
    requireCondition(comparison.behind_by === 0, 'Ветка PR отстаёт от main; сначала обновите её');

    const {data: runs} = await github.rest.actions.listWorkflowRuns({
      ...repository, workflow_id: 'java.yml', head_sha: sha, event: 'pull_request', per_page: 1,
    });
    const run = runs.workflow_runs[0];
    requireCondition(run && run.head_sha === sha && run.event === 'pull_request' &&
      run.pull_requests.some(candidate => candidate.number === issue.number) && run.status === 'completed' &&
      run.conclusion === 'success', 'Последний CI для текущего head должен завершиться успешно');
    const jobs = await github.paginate(github.rest.actions.listJobsForWorkflowRun, {
      ...repository, run_id: run.id, filter: 'latest', per_page: 100,
    });
    for (const [name, marker] of REQUIRED_JOBS) {
      let present = true;
      if (marker) {
        try {
          await github.rest.repos.getContent({...repository, path: marker, ref: sha});
        } catch (error) {
          if (error.status !== 404) throw error;
          present = false;
        }
      }
      const conclusion = present ? 'success' : 'skipped';
      const job = jobs.find(candidate => candidate.name === name);
      requireCondition(job?.status === 'completed' && job.conclusion === conclusion,
        'Обязательная проверка ' + name + ' отсутствует или не прошла');
      const {data: check} = await github.rest.checks.get({
        ...repository, check_run_id: Number(job.check_run_url.split('/').pop()),
      });
      requireCondition(check.app?.id === ACTIONS_APP_ID && check.name === name &&
        check.status === 'completed' && check.conclusion === conclusion,
      'Проверка ' + name + ' должна принадлежать GitHub Actions и завершиться успешно');
    }

    const reviews = await github.paginate(github.rest.pulls.listReviews, {...pullRequest, per_page: 100});
    const decisions = new Map();
    for (const review of reviews) {
      if (['APPROVED', 'CHANGES_REQUESTED', 'DISMISSED'].includes(review.state)) {
        decisions.set(review.user?.login ?? review.id, review.state);
      }
    }
    requireCondition(![...decisions.values()].includes('CHANGES_REQUESTED'),
      'В PR остаются запрошенные изменения');
    let cursor = null;
    do {
      const result = await github.graphql(`
        query($owner: String!, $repo: String!, $number: Int!, $cursor: String) {
          repository(owner: $owner, name: $repo) {
            pullRequest(number: $number) {
              reviewThreads(first: 100, after: $cursor) {
                nodes { isResolved }
                pageInfo { hasNextPage endCursor }
              }
            }
          }
        }`, {...repository, number: issue.number, cursor});
      const threads = result.repository.pullRequest.reviewThreads;
      requireCondition(threads.nodes.every(thread => thread.isResolved),
        'В PR остаются незавершённые обсуждения ревью');
      cursor = threads.pageInfo.hasNextPage ? threads.pageInfo.endCursor : null;
    } while (cursor);

    // Проверки API занимают время: перед изменением PR повторно сверяем его head и main.
    const {data: fresh} = await github.rest.pulls.get(pullRequest);
    const {data: main} = await github.rest.repos.getBranch({...repository, branch: 'main'});
    requireCondition(main.commit.sha === comparison.base_commit.sha &&
      fresh.head.sha === sha && fresh.base.sha === pull.base.sha &&
      fresh.state === 'open' && !fresh.draft && fresh.title === pull.title && fresh.base.ref === 'main',
    'PR или main изменились во время проверки; повторите /approve');
    return pull;
  }

  const approved = await inspect();
  await bot.rest.pulls.createReview({
    ...pullRequest, event: 'APPROVE', commit_id: approved.head.sha,
    body: 'Владелец разрешил слияние командой /approve. CI текущего head прошёл проверку.',
  });
  // Согласование не разрешает сливать новый коммит или обходить правила защиты ветки.
  const mergeable = await inspect(approved.head.sha);
  const {data: merged} = await bot.rest.pulls.merge({
    ...pullRequest, sha: approved.head.sha, merge_method: 'merge',
    commit_title: mergeable.title, commit_message: '',
  });
  requireCondition(merged.merged, 'GitHub отклонил слияние; проверьте обязательные правила PR');
};
