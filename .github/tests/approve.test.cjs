const assert = require('node:assert/strict');
const test = require('node:test');
const approveAndMerge = require('../scripts/approve.cjs');

function scenario() {
  const context = {
    actor: 'principalwater', repo: {owner: 'principalwater', repo: 'java-engineering-course'},
    payload: {issue: {number: 7, pull_request: {}}, comment: {body: '/approve', user: {login: 'principalwater'}}},
  };
  const pull = {
    state: 'open', draft: false, mergeable: true, title: 'SPRINT-03: add the blog',
    user: {login: 'principalwater'}, base: {ref: 'main', sha: 'base'},
    head: {ref: 'feature/sprint03-spring-blog', sha: 'head', repo: {full_name: 'principalwater/java-engineering-course'}},
  };
  const run = {id: 99, head_sha: 'head', event: 'pull_request', pull_requests: [{number: 7}],
    status: 'completed', conclusion: 'success'};
  const jobs = ['projects', 'exercises', 'sprint03'].map((name, index) => ({
    name, status: 'completed', conclusion: 'success', check_run_url: 'https://api.github.com/check-runs/' + index,
  }));
  const checks = jobs.map(job => ({...job, app: {id: 15368}}));
  const reviews = [];
  const effects = [];
  let gets = 0;
  const state = {context, pull, run, jobs, checks, reviews, effects, behind: 0, botLogin: 'elementary-flow-bot'};
  const github = {
    rest: {
      pulls: {
        get: async () => {
          gets += 1;
          if (state.staleBeforeReview && gets === 2) pull.head.sha = 'changed';
          return {data: structuredClone(pull)};
        },
        listReviews: async () => ({data: reviews}),
      },
      repos: {
        compareCommitsWithBasehead: async () => ({data: {behind_by: state.behind, base_commit: {sha: 'base'}}}),
        getBranch: async () => ({data: {commit: {sha: state.staleBase ? 'changed-base' : 'base'}}}),
        getContent: async parameters => {
          if (state.contentError) throw state.contentError;
          if (parameters.ref !== 'head' || state.absentMarkers?.has(parameters.path)) {
            throw Object.assign(new Error('Не найдено'), {status: 404});
          }
          return {data: {type: 'file'}};
        },
      },
      actions: {
        listWorkflowRuns: async () => ({data: {workflow_runs: [run]}}),
        listJobsForWorkflowRun: async () => ({data: {jobs}}),
      },
      checks: {get: async parameters => ({data: checks[parameters.check_run_id]})},
    },
    paginate: async (method, parameters) => {
      const {data} = await method(parameters);
      return data.jobs ?? data;
    },
    graphql: async () => ({repository: {pullRequest: {reviewThreads: {
      nodes: [{isResolved: !state.unresolved}], pageInfo: {hasNextPage: false, endCursor: null},
    }}}}),
  };
  const bot = {rest: {
    users: {getAuthenticated: async () => ({data: {login: state.botLogin}})},
    pulls: {
      merge: async parameters => {effects.push(['merge', parameters]); return {data: {merged: true}};},
      createReview: async parameters => {
      effects.push(['review', parameters]);
      if (state.staleAfterReview) pull.head.sha = 'changed';
      return {data: {state: 'APPROVED'}};
    }},
  }};
  return {...state, state, github, bot};
}

// Ни чужая команда, ни красный CI, ни сменившийся head не дают слияние от имени владельца.
test('согласование допускает только проверенный head и сохраняет историю merge', async t => {
  const denials = [
    ['чужой actor', fixture => {fixture.context.actor = 'other';}, /только владельцу/],
    ['другая команда', fixture => {fixture.context.payload.comment.body = '/approve now';}, /только владельцу/],
    ['многострочный title', fixture => {fixture.pull.title += '\n';}, /Заголовок PR/],
    ['токен владельца вместо bot', fixture => {fixture.state.botLogin = 'principalwater';}, /elementary-flow-bot/],
    ['красный CI', fixture => {fixture.run.conclusion = 'failure';}, /Последний CI/],
    ['push вместо полного PR CI', fixture => {fixture.run.event = 'push';}, /Последний CI/],
    ['CI другого PR', fixture => {fixture.run.pull_requests = [{number: 8}];}, /Последний CI/],
    ['старый CI', fixture => {fixture.run.head_sha = 'previous';}, /Последний CI/],
    ['непройденный job', fixture => {fixture.jobs[1].conclusion = 'failure';}, /Обязательная проверка/],
    ['пропущен существующий модуль', fixture => {
      fixture.jobs[2].conclusion = 'skipped'; fixture.checks[2].conclusion = 'skipped';
    }, /Обязательная проверка/],
    ['403 не означает отсутствующий модуль', fixture => {
      fixture.state.contentError = Object.assign(new Error('Доступ запрещён'), {status: 403});
    }, /Доступ запрещён/],
    ['поддельный check app', fixture => {fixture.checks[0].app.id = 1;}, /GitHub Actions/],
    ['отставание от main', fixture => {fixture.state.behind = 1;}, /отстаёт/],
    ['запрошенные изменения', fixture => {fixture.reviews.push({id: 1, user: {login: 'reviewer'}, state: 'CHANGES_REQUESTED'});}, /запрошенные изменения/],
    ['незавершённое обсуждение', fixture => {fixture.state.unresolved = true;}, /незавершённые обсуждения/],
    ['main сменился во время проверки', fixture => {fixture.state.staleBase = true;}, /изменились/],
    ['head сменился до review', fixture => {fixture.state.staleBeforeReview = true;}, /изменились/],
  ];
  for (const [name, configure, message] of denials) {
    await t.test(name, async () => {
      const fixture = scenario();
      configure(fixture);
      await assert.rejects(approveAndMerge(fixture), message);
      assert.deepEqual(fixture.effects, []);
    });
  }
  await t.test('head сменился после review', async () => {
    const fixture = scenario();
    fixture.state.staleAfterReview = true;
    await assert.rejects(approveAndMerge(fixture), /Head PR изменился/);
    assert.equal(fixture.effects.length, 1);
    assert.equal(fixture.effects[0][0], 'review');
    assert.equal(fixture.effects[0][1].commit_id, 'head');
  });
  await t.test('IDE PR без проекта допускает честный skipped', async () => {
    const fixture = scenario();
    fixture.state.absentMarkers = new Set(['projects/sprint03-spring-blog/pom.xml']);
    fixture.jobs[2].conclusion = 'skipped';
    fixture.checks[2].conclusion = 'skipped';
    await approveAndMerge(fixture);
    assert.deepEqual(fixture.effects.map(effect => effect[0]), ['review', 'merge']);
  });
  await t.test('авторизованная команда', async () => {
    const fixture = scenario();
    await approveAndMerge(fixture);
    assert.equal(fixture.effects[0][0], 'review');
    assert.equal(fixture.effects[0][1].event, 'APPROVE');
    assert.equal(fixture.effects[0][1].commit_id, 'head');
    assert.equal(fixture.effects[1][0], 'merge');
    assert.equal(fixture.effects[1][1].sha, 'head');
    assert.equal(fixture.effects[1][1].merge_method, 'merge');
    assert.equal(fixture.effects[1][1].commit_title, 'SPRINT-03: add the blog');
    assert.equal(fixture.effects[1][1].commit_message, '');
  });
});
