module.exports = async function waitForMainCI({github, context, sha}) {
  if (!/^[a-f0-9]{40}$/.test(sha ?? '')) throw new Error('Invalid merged commit SHA');
  const deadline = Date.now() + 22 * 60 * 1000;
  while (Date.now() < deadline) {
    const {data} = await github.rest.actions.listWorkflowRuns({
      ...context.repo, workflow_id: 'java.yml', branch: 'main', head_sha: sha,
      event: 'push', per_page: 1,
    });
    const run = data.workflow_runs[0];
    if (run) {
      if (run.head_sha !== sha || run.head_branch !== 'main' || run.event !== 'push') {
        throw new Error('The returned CI run does not match the merged main commit');
      }
      if (run.status === 'completed') {
        if (run.conclusion !== 'success') throw new Error('Merged main CI failed: ' + run.html_url);
        return run;
      }
    }
    await new Promise(resolve => setTimeout(resolve, 30000));
  }
  throw new Error('Timed out waiting for merged main CI: ' + sha);
};
