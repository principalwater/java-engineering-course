const OWNER = 'principalwater';
const REVIEW_BOT = 'elementary-flow-bot';
const ACTIONS_APP_ID = 15368;
const REQUIRED_JOBS = new Map([
  ['projects', null],
  ['exercises', 'exercises/sprint01/junit5/pom.xml'],
  ['sprint03', 'projects/sprint03-spring-blog/pom.xml'],
  ['sprint04', 'projects/sprint04-spring-boot-blog/build.gradle'],
]);

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

module.exports = async function approveAndMerge({github, bot, context}) {
  const {issue, comment} = context.payload;
  requireCondition(context.actor === OWNER && comment?.user?.login === OWNER &&
    comment.body === '/approve' && issue?.pull_request && context.repo.owner === OWNER,
  'Only the owner can use /approve in a pull request comment');
  const repository = context.repo;
  const pullRequest = {...repository, pull_number: issue.number};
  const identity = await bot.rest.users.getAuthenticated();
  requireCondition(identity.data.login === REVIEW_BOT,
    'The approval token must belong to elementary-flow-bot');

  const {data: initial} = await github.rest.pulls.get(pullRequest);
  if (initial.merged) {
    requireCondition(initial.user.login === OWNER && initial.base.ref === 'main' &&
      initial.head.repo?.full_name === repository.owner + '/' + repository.repo,
    'The merged pull request must belong to the owner within this repository');
    return initial.merge_commit_sha;
  }

  async function inspect(expectedSha, initialPull) {
    const pull = initialPull ?? (await github.rest.pulls.get(pullRequest)).data;
    requireCondition(pull.state === 'open' && !pull.draft && pull.base.ref === 'main' &&
      pull.user.login === OWNER && pull.head.ref !== 'main' &&
      pull.head.repo?.full_name === repository.owner + '/' + repository.repo,
    'An open, non-draft pull request from the owner within this repository must target main');
    requireCondition(/^SPRINT-\d{2}: [a-z][\x20-\x7e]*$/.test(pull.title),
      'The pull request title must be a single English line: SPRINT-NN: description');
    requireCondition(pull.mergeable === true, 'Mergeability is unknown or the pull request has conflicts');
    const sha = pull.head.sha;
    requireCondition(!expectedSha || sha === expectedSha, 'The pull request head changed; run /approve again');
    const {data: comparison} = await github.rest.repos.compareCommitsWithBasehead({
      ...repository, basehead: 'main...' + sha,
    });
    requireCondition(comparison.behind_by === 0, 'The pull request branch is behind main; update it first');

    const {data: runs} = await github.rest.actions.listWorkflowRuns({
      ...repository, workflow_id: 'java.yml', head_sha: sha, event: 'pull_request', per_page: 1,
    });
    const run = runs.workflow_runs[0];
    requireCondition(run && run.head_sha === sha && run.event === 'pull_request' &&
      run.pull_requests.some(candidate => candidate.number === issue.number) && run.status === 'completed' &&
      run.conclusion === 'success', 'The latest pull request CI run for the current head must complete successfully');
    const {data: pushes} = await github.rest.actions.listWorkflowRuns({
      ...repository, workflow_id: 'java.yml', branch: pull.head.ref, head_sha: sha, event: 'push', per_page: 1,
    });
    const push = pushes.workflow_runs[0];
    requireCondition(push && push.head_sha === sha && push.head_branch === pull.head.ref && push.event === 'push' &&
      push.status === 'completed' && push.conclusion === 'success',
    'The latest push CI run for the current head must complete successfully');
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
        'Required check ' + name + ' is missing or did not pass');
      const {data: check} = await github.rest.checks.get({
        ...repository, check_run_id: Number(job.check_run_url.split('/').pop()),
      });
      requireCondition(check.app?.id === ACTIONS_APP_ID && check.name === name &&
        check.status === 'completed' && check.conclusion === conclusion,
      'Check ' + name + ' must belong to GitHub Actions and complete successfully');
    }

    const reviews = await github.paginate(github.rest.pulls.listReviews, {...pullRequest, per_page: 100});
    const decisions = new Map();
    for (const review of reviews) {
      if (['APPROVED', 'CHANGES_REQUESTED', 'DISMISSED'].includes(review.state)) {
        decisions.set(review.user?.login ?? review.id, review.state);
      }
    }
    requireCondition(![...decisions.values()].includes('CHANGES_REQUESTED'),
      'The pull request still has requested changes');
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
        'The pull request still has unresolved review threads');
      cursor = threads.pageInfo.hasNextPage ? threads.pageInfo.endCursor : null;
    } while (cursor);

    // API checks take time: recheck the pull request head and main before making changes.
    const {data: fresh} = await github.rest.pulls.get(pullRequest);
    const {data: main} = await github.rest.repos.getBranch({...repository, branch: 'main'});
    requireCondition(main.commit.sha === comparison.base_commit.sha &&
      fresh.head.sha === sha && fresh.base.sha === pull.base.sha &&
      fresh.state === 'open' && !fresh.draft && fresh.title === pull.title && fresh.base.ref === 'main',
    'The pull request or main changed during validation; run /approve again');
    return {pull, alreadyApproved: reviews.some(review => review.user?.login === REVIEW_BOT &&
      review.commit_id === sha && review.state === 'APPROVED')};
  }

  const approved = await inspect(undefined, initial);
  if (!approved.alreadyApproved) {
    await bot.rest.pulls.createReview({
      ...pullRequest, event: 'APPROVE', commit_id: approved.pull.head.sha,
      body: 'The owner authorized merging with /approve. Pull request and push CI for the current head passed validation.',
    });
  }
  // Approval does not authorize merging a new commit or bypassing branch protection.
  const {pull: mergeable} = await inspect(approved.pull.head.sha);
  const {data: merged} = await bot.rest.pulls.merge({
    ...pullRequest, sha: approved.pull.head.sha, merge_method: 'merge',
    commit_title: mergeable.title, commit_message: '',
  });
  requireCondition(merged.merged, 'GitHub rejected the merge; check the required pull request rules');
  return merged.sha;
};
