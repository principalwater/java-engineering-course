const assert = require('node:assert/strict');
const test = require('node:test');
const waitForMainCI = require('../scripts/wait-main-ci.cjs');

const SHA = 'a'.repeat(40);
const context = {repo: {owner: 'principalwater', repo: 'java-engineering-course'}};
const successful = {head_sha: SHA, head_branch: 'main', event: 'push',
  status: 'completed', conclusion: 'success', html_url: 'https://github.com/example/actions/runs/1'};

function fixture(runs) {
  return {github: {rest: {actions: {listWorkflowRuns: async () => ({data: {workflow_runs: runs}})}}},
    context, sha: SHA};
}

// A green PR is insufficient: delivery must observe successful CI for the exact merged main commit.
test('main CI rejects failure, an unrelated run and invalid commit input', async () => {
  for (const run of [
    {...successful, conclusion: 'failure'},
    {...successful, head_sha: 'b'.repeat(40)},
    {...successful, head_branch: 'feature'},
    {...successful, event: 'pull_request'},
  ]) {
    await assert.rejects(waitForMainCI(fixture([run])), /Merged main CI failed|does not match/);
  }
  await assert.rejects(waitForMainCI({...fixture([]), sha: '--all'}), /Invalid merged commit SHA/);
  assert.equal(await waitForMainCI(fixture([successful])), successful);
});

test('main CI waits through missing and running states, with a bounded deadline', async t => {
  t.mock.timers.enable({apis: ['Date', 'setTimeout'], now: 0});
  const inputs = fixture([]);
  let calls = 0;
  inputs.github.rest.actions.listWorkflowRuns = async () => ({data: {workflow_runs:
    ++calls === 1 ? [] : [{...successful, status: calls === 2 ? 'in_progress' : 'completed'}]}});
  const pending = waitForMainCI(inputs);
  await new Promise(setImmediate);
  t.mock.timers.tick(30000);
  await new Promise(setImmediate);
  t.mock.timers.tick(30000);
  assert.equal((await pending).conclusion, 'success');

  const timeout = assert.rejects(waitForMainCI(fixture([])), /Timed out waiting/);
  await new Promise(setImmediate);
  t.mock.timers.tick(22 * 60 * 1000);
  await timeout;
});
