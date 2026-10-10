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
  const pushRun = {...run, id: 100, event: 'push', head_branch: pull.head.ref, pull_requests: []};
  const jobs = ['projects', 'exercises', 'sprint03', 'sprint04'].map((name, index) => ({
    name, status: 'completed', conclusion: 'success', check_run_url: 'https://api.github.com/check-runs/' + index,
  }));
  const checks = jobs.map(job => ({...job, app: {id: 15368}}));
  const reviews = [];
  const effects = [];
  let gets = 0;
  const state = {context, pull, run, pushRun, jobs, checks, reviews, effects, behind: 0, botLogin: 'elementary-flow-bot'};
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
            throw Object.assign(new Error('Not found'), {status: 404});
          }
          return {data: {type: 'file'}};
        },
      },
      actions: {
        listWorkflowRuns: async parameters => ({data: {workflow_runs: [parameters.event === 'push' ? pushRun : run]}}),
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
      merge: async parameters => {effects.push(['merge', parameters]); return {data: {merged: true, sha: 'merged'}};},
      createReview: async parameters => {
      effects.push(['review', parameters]);
      if (state.staleAfterReview) pull.head.sha = 'changed';
      return {data: {state: 'APPROVED'}};
    }},
  }};
  return {...state, state, github, bot};
}

// An unauthorized command, failed CI or changed head cannot permit merging on behalf of the owner.
test('approval accepts only a verified head and preserves merge history', async t => {
  const denials = [
    ['unauthorized actor', fixture => {fixture.context.actor = 'other';}, /Only the owner/],
    ['different command', fixture => {fixture.context.payload.comment.body = '/approve now';}, /Only the owner/],
    ['multiline title', fixture => {fixture.pull.title += '\n';}, /pull request title/],
    ['owner token instead of bot token', fixture => {fixture.state.botLogin = 'principalwater';}, /elementary-flow-bot/],
    ['failed CI', fixture => {fixture.run.conclusion = 'failure';}, /latest pull request CI/],
    ['push run instead of full pull request CI', fixture => {fixture.run.event = 'push';}, /latest pull request CI/],
    ['CI for another pull request', fixture => {fixture.run.pull_requests = [{number: 8}];}, /latest pull request CI/],
    ['stale CI', fixture => {fixture.run.head_sha = 'previous';}, /latest pull request CI/],
    ['failed push CI', fixture => {fixture.pushRun.conclusion = 'failure';}, /latest push CI/],
    ['pending push CI', fixture => {fixture.pushRun.status = 'in_progress';}, /latest push CI/],
    ['stale push CI', fixture => {fixture.pushRun.head_sha = 'previous';}, /latest push CI/],
    ['push CI from another branch', fixture => {fixture.pushRun.head_branch = 'other';}, /latest push CI/],
    ['missing Boot job', fixture => {fixture.jobs.pop(); fixture.checks.pop();}, /Required check sprint04/],
    ['failed job', fixture => {fixture.jobs[1].conclusion = 'failure';}, /Required check/],
    ['existing module skipped', fixture => {
      fixture.jobs[2].conclusion = 'skipped'; fixture.checks[2].conclusion = 'skipped';
    }, /Required check/],
    ['403 does not mean the module is absent', fixture => {
      fixture.state.contentError = Object.assign(new Error('Access denied'), {status: 403});
    }, /Access denied/],
    ['spoofed check app', fixture => {fixture.checks[0].app.id = 1;}, /GitHub Actions/],
    ['branch behind main', fixture => {fixture.state.behind = 1;}, /behind main/],
    ['requested changes', fixture => {fixture.reviews.push({id: 1, user: {login: 'reviewer'}, state: 'CHANGES_REQUESTED'});}, /requested changes/],
    ['unresolved review thread', fixture => {fixture.state.unresolved = true;}, /unresolved review threads/],
    ['main changed during validation', fixture => {fixture.state.staleBase = true;}, /changed during validation/],
    ['head changed before review', fixture => {fixture.state.staleBeforeReview = true;}, /changed during validation/],
  ];
  for (const [name, configure, message] of denials) {
    await t.test(name, async () => {
      const fixture = scenario();
      configure(fixture);
      await assert.rejects(approveAndMerge(fixture), message);
      assert.deepEqual(fixture.effects, []);
    });
  }
  await t.test('head changed after review', async () => {
    const fixture = scenario();
    fixture.state.staleAfterReview = true;
    await assert.rejects(approveAndMerge(fixture), /pull request head changed/);
    assert.equal(fixture.effects.length, 1);
    assert.equal(fixture.effects[0][0], 'review');
    assert.equal(fixture.effects[0][1].commit_id, 'head');
  });
  await t.test('an IDE pull request without a project allows a legitimate skipped job', async () => {
    const fixture = scenario();
    fixture.state.absentMarkers = new Set(['projects/sprint03-spring-blog/pom.xml', 'projects/sprint04-spring-boot-blog/build.gradle']);
    fixture.jobs[2].conclusion = 'skipped';
    fixture.checks[2].conclusion = 'skipped';
    fixture.jobs[3].conclusion = 'skipped';
    fixture.checks[3].conclusion = 'skipped';
    await approveAndMerge(fixture);
    assert.deepEqual(fixture.effects.map(effect => effect[0]), ['review', 'merge']);
  });
  await t.test('authorized command', async () => {
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
  await t.test('an existing approval for the same head is reused after a retry', async () => {
    const fixture = scenario();
    fixture.reviews.push({id: 1, user: {login: 'elementary-flow-bot'}, commit_id: 'head', state: 'APPROVED'});
    assert.equal(await approveAndMerge(fixture), 'merged');
    assert.deepEqual(fixture.effects.map(effect => effect[0]), ['merge']);
  });
  await t.test('a repeated delivery for a merged pull request has no new review or merge', async () => {
    const fixture = scenario();
    Object.assign(fixture.pull, {state: 'closed', merged: true, merge_commit_sha: 'merged'});
    assert.equal(await approveAndMerge(fixture), 'merged');
    assert.deepEqual(fixture.effects, []);
  });
});
