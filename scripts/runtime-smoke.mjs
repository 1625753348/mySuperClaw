import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { setTimeout as sleep } from 'node:timers/promises';

// Run against an isolated PostgreSQL test database and a dedicated Redis logical database.
const jar = process.argv[2];
assert.ok(jar && process.env.ENTERPRISE_DB_URL && process.env.ENTERPRISE_DB_PASSWORD, 'JAR and test DB configuration required');

const fixtureRows = [
    { region: '华东', product: 'A', customer: '企业', previous: 120.25, current: 90.25 },
    { region: '华南', product: 'B', customer: '个人', previous: 80.50, current: 100.50 }
];
const contextHistory = [];
let awaitingPlanner;
const enteredPlanner = new Promise(resolve => { awaitingPlanner = resolve; });
let submits = 0;
let polls = 0;
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const tenant = 'runtime-smoke-' + randomUUID();
const kid = 'runtime-smoke';

async function bodyOf(request) {
    let text = '';
    for await (const part of request) text += part;
    return JSON.parse(text);
}

const fixture = createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    const route = new URL(req.url, 'http://fixture');
    if (route.pathname === '/jwks') {
        return res.end(JSON.stringify({ keys: [{ ...publicKey.export({ format: 'jwk' }), use: 'sig', alg: 'RS256', kid }] }));
    }
    if (route.pathname === '/chat/completions') {
        const request = await bodyOf(req);
        const context = JSON.parse(request.messages.find(message => message.role === 'user').content);
        contextHistory.push(context);
        if (contextHistory.length === 1) {
            awaitingPlanner();
            return; // The worker will be killed with its planner lease still held.
        }
        const dimensions = context.iteration === 1 ? ['region'] : context.missing;
        return res.end(JSON.stringify({
            id: 'fixture', object: 'chat.completion', created: 1, model: 'fixture',
            choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify({ dimensions }) }, finish_reason: 'stop' }],
            usage: { prompt_tokens: 10, completion_tokens: 9, total_tokens: 19 }
        }));
    }
    if (route.pathname === '/jobs/submit') {
        const request = await bodyOf(req);
        submits++;
        if (request.jobId === 'unknown-report') return res.writeHead(503).end('{}');
        return res.writeHead(202).end(JSON.stringify({ jobId: request.jobId }));
    }
    if (route.pathname === '/jobs/status' && route.searchParams.get('jobId') === 'approved-report') {
        polls++;
        return res.end(JSON.stringify(polls < 2 ? { status: 'RUNNING' } : { status: 'SUCCEEDED', result: { rows: fixtureRows } }));
    }
    return res.writeHead(404).end('{}');
});
fixture.listen(0, '127.0.0.1');
await once(fixture, 'listening');
const issuer = 'http://127.0.0.1:' + fixture.address().port;

function bearer(scope, user = 'customer', tenantId = tenant) {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const claims = encode({ alg: 'RS256', kid }) + '.' + encode({
        iss: issuer, aud: 'runtime', exp: Math.floor(Date.now() / 1000) + 600, sub: user,
        agent_id: 'fixture-agent', tenant_id: tenantId, scope, roles: ['runtime-tester']
    });
    return claims + '.' + sign('RSA-SHA256', Buffer.from(claims), privateKey).toString('base64url');
}

const appEnv = { ...process.env };
for (const name of ['DEEPSEEK_API_KEY', 'DASHSCOPE_API_KEY', 'EMBEDDING_API_KEY', 'ENTERPRISE_GATEWAY_SEMANTIC_ENABLED']) delete appEnv[name];
appEnv.SPRING_APPLICATION_JSON = JSON.stringify({ enterprise: {
    gateway: {
        jwt: { issuer, audience: 'runtime', 'jwk-set-uri': issuer + '/jwks' },
        'job-key': randomBytes(32).toString('base64'), 'job-interval-ms': 150,
        'async-targets': { sales: { 'submit-uri': issuer + '/jobs/submit', 'status-uri': issuer + '/jobs/status', 'allowed-tenants': [tenant] } }
    },
    runtime: { 'lease-seconds': 3, planner: { enabled: true, 'base-url': issuer, 'api-key': 'fixture', model: 'fixture' } }
}, spring: { data: { redis: { database: Number(process.env.ENTERPRISE_RUNTIME_TEST_REDIS_DB ?? 13) } } } });

const processes = [];
function start(worker) {
    const args = ['-jar', jar, '--spring.profiles.active=storage,gateway,runtime' + (worker ? ',worker' : ''),
        '--server.address=127.0.0.1', '--server.port=0', '--spring.main.banner-mode=off'];
    const child = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java', args,
        { env: appEnv, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const app = { child, closed: once(child, 'close'), logs: '', worker };
    processes.push(app);
    app.ready = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(Error('Application failed to start')), 35_000);
        const collect = buffer => {
            app.logs = (app.logs + buffer.toString()).slice(-60_000);
            if (/Started EnterpriseAgentApplication/.test(app.logs)) {
                clearTimeout(timer);
                const port = app.logs.match(/Tomcat started on port (\d+)/)?.[1];
                resolve(port && 'http://127.0.0.1:' + port);
            }
        };
        child.stdout.on('data', collect);
        child.stderr.on('data', collect);
        child.once('exit', () => { clearTimeout(timer); reject(Error('Application shut down')); });
        child.once('error', error => { clearTimeout(timer); reject(error); });
    });
    return app;
}

let base;
const owner = bearer('runs.submit runs.read tools.invoke');
const admin = bearer('tools.admin', 'admin');
const reviewer = bearer('tools.approve', 'reviewer');
const endpoint = id => '/v1/runs/' + id;

async function api(method, path, expected, token = owner, body, key) {
    const headers = {};
    if (token) headers.Authorization = 'Bearer ' + token;
    if (key) headers['Idempotency-Key'] = key;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(8_000) });
    const text = await response.text();
    assert.ok([expected].flat().includes(response.status), method + ' ' + path + ' ' + response.status + ' ' + text);
    return text ? JSON.parse(text) : null;
}

async function until(id, expected) {
    const deadline = Date.now() + 30_000;
    let view;
    while (Date.now() < deadline) {
        view = await api('GET', endpoint(id), 200);
        if (view.status === expected) return view;
        if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'NEEDS_RECONCILIATION'].includes(view.status)) break;
        await sleep(200);
    }
    assert.equal(view?.status, expected, JSON.stringify(view));
}

async function sse(id, last, stop) {
    const response = await fetch(base + endpoint(id) + '/events', {
        headers: { Authorization: 'Bearer ' + owner, 'Last-Event-ID': String(last) }, signal: AbortSignal.timeout(16_000)
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /text\/event-stream/);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let chunk = '';
    const result = [];
    try {
        while (true) {
            const item = await reader.read();
            if (item.done) return result;
            chunk += decoder.decode(item.value, { stream: true }).replaceAll('\r', '');
            let index;
            while ((index = chunk.indexOf('\n\n')) >= 0) {
                const lines = chunk.slice(0, index).split('\n');
                chunk = chunk.slice(index + 2);
                const seq = lines.find(line => line.startsWith('id:'));
                if (!seq) continue;
                const event = { seq: Number(seq.slice(3)), type: lines.find(line => line.startsWith('event:')).slice(6),
                    data: JSON.parse(lines.find(line => line.startsWith('data:')).slice(5)) };
                result.push(event);
                if (stop?.(event)) return result;
            }
        }
    } finally {
        await reader.cancel().catch(() => {});
    }
}

try {
    base = await start(false).ready;
    const workflow = { mode: 'WORKFLOW', goal: '生成固定销售报表', dimensions: ['region', 'product'], rows: fixtureRows };
    const agent = { ...workflow, mode: 'AGENT', goal: '核对销售变动的原因', maxIterations: 2 };
    const fixed = await api('POST', '/v1/runs', 202, owner, workflow, 'fixed');
    assert.equal(fixed.status, 'QUEUED');
    assert.equal((await api('POST', '/v1/runs', 202, owner, workflow, 'fixed')).runId, fixed.runId);
    await api('POST', '/v1/runs', 409, owner, { ...workflow, goal: 'different' }, 'fixed');
    await api('GET', endpoint(fixed.runId), 404, bearer('runs.read', 'other-user'));
    await api('GET', endpoint(fixed.runId), 404, bearer('runs.read', 'customer', 'elsewhere'));
    await api('GET', endpoint(fixed.runId), 401, null);
    const stop = await api('POST', '/v1/runs', 202, owner, workflow, 'cancel-before-worker');
    assert.equal((await api('POST', endpoint(stop.runId) + '/cancel', 200, owner, undefined, 'cancel')).status, 'CANCELLED');
    const initial = await sse(fixed.runId, 0, event => event.type === 'run.accepted');
    assert.equal(initial[0].seq, 1);
    const dynamic = await api('POST', '/v1/runs', 202, owner, agent, 'dynamic');
    const killed = start(true);
    await killed.ready;
    await Promise.race([enteredPlanner, sleep(20_000, undefined, { ref: false }).then(() => { throw Error('Planner was not called'); })]);
    killed.child.kill();
    await killed.closed;
    const worker = start(true);
    await worker.ready;
    await until(fixed.runId, 'SUCCEEDED');
    const replay = await sse(fixed.runId, initial[0].seq);
    assert.ok(replay.length > 3);
    assert.equal(replay[0].seq, 2);
    assert.equal(replay.at(-1).type, 'run.finished');
    const accepted = await until(dynamic.runId, 'SUCCEEDED');
    assert.equal(accepted.iteration, 2);
    assert.equal(accepted.planVersion, 3);
    assert.equal(contextHistory.length, 3);
    assert.ok(contextHistory[2].completed.includes('region'));
    assert.ok(contextHistory[2].observations.source);
    assert.deepEqual(contextHistory[2].missing, ['product']);
    const report = await api('GET', endpoint(dynamic.runId) + '/result', 200);
    assert.equal(report.totals.change, -10);
    assert.deepEqual(Object.keys(report.dimensions).sort(), ['product', 'region']);
    const history = await sse(dynamic.runId, 0);
    assert.ok(history.some(event => event.type === 'run.gate_evaluated' && event.data.verdict === 'RETRYABLE_FAIL'));
    assert.ok(history.some(event => event.type === 'run.plan_changed' && event.data.reused.includes('region')));
    const limited = await api('POST', '/v1/runs', 202, owner, { ...agent, maxIterations: 1 }, 'gate-limit');
    assert.equal((await until(limited.runId, 'FAILED')).reason, 'HARD_GATE_BUDGET_EXHAUSTED');
    await api('GET', endpoint(limited.runId) + '/result', 404);
    console.log('Runtime passed authenticated 202, idempotency, cancel, worker crash recovery, parallel DAG, Ralph and SSE replay');

    const name = 'sales_fixture';
    const tool = '/v1/admin/tools/' + name;
    await api('POST', '/v1/admin/tools', 201, admin, { name, description: 'Sales fixture', riskLevel: 'MEDIUM' });
    await api('POST', tool + '/versions', 201, admin, {
        version: '1', protocol: 'ASYNC', targetRef: 'sales', timeoutMs: 3000,
        inputSchema: { type: 'object', required: ['jobId'] },
        outputSchema: { type: 'object', required: ['rows'] }
    });
    await api('POST', tool + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
    for (const action of ['APPROVE', 'EXECUTE']) {
        await api('PUT', tool + '/permissions', 204, admin, { subjectType: 'ROLE', subjectId: 'runtime-tester', action, effect: 'ALLOW' });
    }
    await api('PUT', tool + '/policies/APPROVAL', 200, admin, { expectedVersion: 0, rule: { ttlSeconds: 180 } });
    const usingTool = { ...workflow, rows: undefined, source: { name, version: '1', arguments: { jobId: 'approved-report' } } };
    await api('POST', '/v1/runs', 403, bearer('runs.submit runs.read'), usingTool, 'missing-tool-scope');
    const pending = await api('POST', '/v1/runs', 202, owner, usingTool, 'approval');
    await until(pending.runId, 'WAITING_APPROVAL');
    const approvalEvents = await sse(pending.runId, 0, event => !!event.data.approvalId);
    const approvalId = approvalEvents.at(-1).data.approvalId;
    assert.equal(submits, 0);
    await api('POST', '/v1/tools/approvals/' + approvalId + '/decisions', 200, reviewer, { decision: 'APPROVE' });
    await until(pending.runId, 'SUCCEEDED');
    assert.equal(submits, 1);
    assert.ok(polls >= 2);
    assert.equal((await api('GET', endpoint(pending.runId) + '/result', 200)).totals.current, 190.75);
    const cancelled = await api('POST', '/v1/runs', 202, owner, usingTool, 'cancel-approval');
    await until(cancelled.runId, 'WAITING_APPROVAL');
    const cancelHistory = await sse(cancelled.runId, 0, event => !!event.data.approvalId);
    await api('POST', endpoint(cancelled.runId) + '/cancel', [200, 202], owner, undefined, 'cancel-pending');
    await until(cancelled.runId, 'CANCELLED');
    const cancelledApproval = await api('GET', '/v1/tools/approvals/' + cancelHistory.at(-1).data.approvalId, 200, reviewer);
    assert.equal(cancelledApproval.status, 'CANCELLED');
    assert.equal(submits, 1);
    const uncertain = await api('POST', '/v1/runs', 202, owner,
        { ...usingTool, source: { ...usingTool.source, arguments: { jobId: 'unknown-report' } } }, 'uncertain-effect');
    const uncertainHistory = await sse(uncertain.runId, 0, event => !!event.data.approvalId);
    await api('POST', '/v1/tools/approvals/' + uncertainHistory.at(-1).data.approvalId + '/decisions', 200, reviewer, { decision: 'APPROVE' });
    assert.equal((await until(uncertain.runId, 'NEEDS_RECONCILIATION')).reason, 'TOOL_RESULT_UNKNOWN');
    assert.equal(submits, 2);
    await api('PUT', tool + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'DENY' });
    await api('GET', endpoint(pending.runId) + '/result', 403);
    console.log('Runtime linked approvals, async polling, safe cancellation, unknown-effect reconciliation and revoked result access');
} catch (error) {
    for (const process of processes) console.error('\n--- ' + (process.worker ? 'worker' : 'API') + ' ---\n' + process.logs.slice(-10_000));
    throw error;
} finally {
    for (const app of processes) {
        if (app.child.exitCode === null && !app.child.killed) app.child.kill();
    }
    await Promise.allSettled(processes.map(app => app.closed));
    fixture.closeAllConnections();
    fixture.close();
}
