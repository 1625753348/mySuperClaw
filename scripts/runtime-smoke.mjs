import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { once } from 'node:events';
import { readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { setTimeout as sleep } from 'node:timers/promises';

// Run against an isolated PostgreSQL test database and a dedicated Redis logical database.
const jar = process.argv[2];
assert.ok(jar && process.env.ENTERPRISE_DB_URL && process.env.ENTERPRISE_DB_PASSWORD, 'JAR and test DB configuration required');
const retroFixture = JSON.parse(await readFile(new URL('./fixtures/github-retrospective.json', import.meta.url), 'utf8'));

const fixtureRows = [
    { region: '华东', product: 'A', customer: '企业', previous: 120.25, current: 90.25 },
    { region: '华南', product: 'B', customer: '个人', previous: 80.50, current: 100.50 }
];
const contextHistory = [];
const generalHistory = [];
let generalVariant = 'valid';
let financeSummaries = 0;
let optionalExecutions = 0;
let compositionSummaries = 0;
let compositionOmitSkill = false;
const compositionContexts = [];
let awaitingPlanner;
const enteredPlanner = new Promise(resolve => { awaitingPlanner = resolve; });
let submits = 0;
let polls = 0;
let orderCalls = 0;
let issueReads = 0;
let commitReads = 0;
let refundWrites = 0;
let chatCalls = 0;
let preferenceSeen = false;
let exactWorkflow;
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
    if (route.pathname === '/memory/embeddings') {
        const request = await bodyOf(req);
        const vector = Array(request.dimensions).fill(0);
        vector[request.input.includes('退款') ? 0 : 1] = 1;
        return res.end(JSON.stringify({ data: [{ embedding: vector }] }));
    }
    if (route.pathname === '/chat/completions') {
        const request = await bodyOf(req);
        if (request.messages.some(message => message.role === 'system' && message.content.includes('helpful conversation'))) {
            chatCalls++;
            preferenceSeen ||= request.messages.some(message => message.role === 'system' && message.content.includes('reportStyle=brief'));
            return res.end(JSON.stringify({ id: 'chat-fixture', object: 'chat.completion', created: 1, model: 'fixture',
                choices: [{ index: 0, message: { role: 'assistant', content: '你好，需要我帮你处理什么？' }, finish_reason: 'stop' }] }));
        }
        const context = JSON.parse(request.messages.find(message => message.role === 'user').content);
        if (request.messages.some(message => message.role === 'system' && message.content.includes('general DAG proposal'))) {
            if (context.skill) {
                compositionContexts.push(context);
                const nodes = [
                    { id: 'retro', skill: context.skill.key, dependsOn: [], inputs: {} },
                    { id: 'summary', tool: 'retro_summary_fixture', dependsOn: ['retro'],
                        inputs: { markdown: 'steps.retro.output.markdown' } }
                ];
                const draft = { nodes: compositionOmitSkill ? [
                    { id: 'summary', tool: 'retro_summary_fixture', dependsOn: [], inputs: {} }
                ] : nodes, result: 'summary' };
                return res.end(JSON.stringify({ id: 'sop-fixture', object: 'chat.completion', created: 1, model: 'fixture',
                    choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify(draft) }, finish_reason: 'stop' }] }));
            }
            generalHistory.push(context);
            const resilient = ['retry-success', 'fallback', 'drop', 'invalid-drop-data', 'unknown-effect'].includes(generalVariant);
            const delegatePlan = generalVariant === 'delegate' || generalVariant === 'unknown-subagent' || resilient;
            const drop = ['drop', 'invalid-drop-data'].includes(generalVariant);
            const draft = { nodes: [
                { id: 'evidence', ...(delegatePlan ? { subagent: 'regional' } :
                    { tool: generalVariant === 'denied' ? 'finance_denied' : 'finance_lookup' }),
                    dependsOn: generalVariant === 'cycle' ? ['summary'] : [], inputs: { period: 'input.period' } },
                ...(delegatePlan ? [{ id: 'check', subagent: generalVariant === 'unknown-subagent' ? 'not_configured'
                    : resilient ? drop ? 'optional_drop' : 'optional_check' : 'control',
                    dependsOn: [], inputs: { period: 'input.period' } }] : []),
                { id: 'summary', tool: 'finance_summary', dependsOn: delegatePlan ? ['evidence', 'check'] : ['evidence'],
                    inputs: { evidence: 'steps.evidence.output.evidence',
                        ...(delegatePlan && generalVariant !== 'drop' ? { verification: 'steps.check.output.verification' } : {}) } }
            ], result: 'summary' };
            return res.end(JSON.stringify({ id: 'general-fixture', object: 'chat.completion', created: 1, model: 'fixture',
                choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify(draft) }, finish_reason: 'stop' }] }));
        }
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
    if (route.pathname === '/orders') {
        const { orderId } = await bodyOf(req);
        orderCalls++;
        if (orderId === 'SO777') return res.end(JSON.stringify({ orderId, status: 'SHIPPED', contractId: 'CT-031' }));
        return res.end(JSON.stringify({ orderId, status: 'SHIPPED', privateNote: 'fixture internal field' }));
    }
    if (route.pathname === '/route/contract') {
        const { contractNumber } = await bodyOf(req);
        if (contractNumber !== 'CT-031') return res.writeHead(400).end('{}');
        return res.end(JSON.stringify({ contractNumber, status: 'SIGNED' }));
    }
    if (route.pathname === '/route/calendar') return res.end(JSON.stringify({ events: ['项目周会'] }));
    if (route.pathname === '/finance/lookup') {
        const { period } = await bodyOf(req);
        if (period !== '上季度') return res.writeHead(400).end('{}');
        return res.end(JSON.stringify({ evidence: '上季度回款同比持平' }));
    }
    if (route.pathname === '/finance/summary') {
        const { evidence, verification } = await bodyOf(req);
        if (evidence !== '上季度回款同比持平' || generalVariant === 'delegate' && verification !== '已核对总账') {
            return res.writeHead(400).end('{}');
        }
        financeSummaries++;
        return res.end(JSON.stringify({ answer: generalVariant === 'fallback' ? evidence + '；' + verification : evidence,
            reportPeriod: financeSummaries === 1 || generalVariant === 'wrong-report' ? '本季度' : '上季度',
            evidenceCount: financeSummaries === 1 ? 1 : 2 }));
    }
    if (route.pathname === '/finance/check') {
        const { period } = await bodyOf(req);
        if (period !== '上季度') return res.writeHead(400).end('{}');
        return res.end(JSON.stringify({ verification: '已核对总账' }));
    }
    if (route.pathname === '/finance/optional') {
        await bodyOf(req);
        optionalExecutions++;
        if (generalVariant === 'unknown-effect') return res.writeHead(503).end('{}');
        return res.end(JSON.stringify(generalVariant === 'retry-success' && optionalExecutions === 2
            ? { verification: '已补取总账' } : { incomplete: true }));
    }
    if (route.pathname === '/retro/summary') {
        const { markdown } = await bodyOf(req);
        if (typeof markdown !== 'string' || !retroFixture.expectedTop3.every(id => markdown.includes('/issues/' + id + '#issuecomment-'))) {
            return res.writeHead(400).end('{}');
        }
        compositionSummaries++;
        return res.end(JSON.stringify({ summary: '已从带出处的复盘报告整理改进事项' }));
    }
    if (route.pathname === '/route/knowledge') {
        const { query } = await bodyOf(req);
        if (!query.includes('退款政策')) return res.writeHead(400).end('{}');
        return res.end(JSON.stringify({ answer: '测试退款政策以工单核验为准。', sources: [{ id: 'policy-test', title: '退款政策手册' }] }));
    }
    if (route.pathname === '/sales') {
        const { period } = await bodyOf(req);
        if (period !== '上季度') return res.writeHead(400).end('{}');
        return res.end(JSON.stringify({ rows: fixtureRows }));
    }
    if (route.pathname === '/retro/issues' || route.pathname === '/retro/commits') {
        const request = await bodyOf(req);
        if (request.repository !== retroFixture.repository || !request.from || !request.to) return res.writeHead(403).end('{}');
        const issues = route.pathname.endsWith('/issues');
        if (issues) issueReads++; else commitReads++;
        const items = issues ? retroFixture.issues : retroFixture.commits;
        const end = Date.parse(retroFixture.now);
        const recent = items.filter(item => Date.parse(item.updatedAt || item.date) >= end - 30 * 86400000);
        return res.end(JSON.stringify({ complete: true, items: recent }));
    }
    if (route.pathname === '/refund/eligible') {
        const { orderId } = await bodyOf(req);
        return res.end(JSON.stringify({ refundable: orderId === 'SO001' }));
    }
    if (route.pathname === '/refund/submit') {
        const { orderId, reason } = await bodyOf(req);
        if (orderId !== 'SO001' || reason !== '重复下单') return res.writeHead(400).end('{}');
        refundWrites++;
        return res.end(JSON.stringify({ refundId: 'fixture-refund-001', status: 'REFUNDED' }));
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
        discovery: { 'embedding-uri': issuer + '/memory/embeddings', 'api-key': 'fixture', model: 'fixture' },
        'job-key': randomBytes(32).toString('base64'), 'job-interval-ms': 150,
        targets: { orders: issuer + '/orders', 'sales-sync': issuer + '/sales',
            'retro-issues': issuer + '/retro/issues', 'retro-commits': issuer + '/retro/commits',
            'refund-eligible': issuer + '/refund/eligible', 'refund-submit': issuer + '/refund/submit',
            'route-contract': issuer + '/route/contract', 'route-calendar': issuer + '/route/calendar',
            'route-knowledge': issuer + '/route/knowledge',
            'finance-lookup': issuer + '/finance/lookup', 'finance-summary': issuer + '/finance/summary',
            'finance-check': issuer + '/finance/check',
            'finance-optional': issuer + '/finance/optional',
            'retro-summary': issuer + '/retro/summary' },
        'async-targets': { sales: { 'submit-uri': issuer + '/jobs/submit', 'status-uri': issuer + '/jobs/status', 'allowed-tenants': [tenant] } }
    },
    runtime: { 'lease-seconds': 3, planner: { enabled: true, 'base-url': issuer, 'api-key': 'fixture', model: 'fixture' },
        'general-planner': { enabled: true, 'base-url': issuer, 'api-key': 'fixture', model: 'fixture' } },
    intent: { dispatch: { 'sales-tool': 'sales_conversation' }, model: { enabled: false },
        chat: { enabled: true, 'base-url': issuer, 'api-key': 'fixture', name: 'fixture' },
        routing: {
            bindings: [
                { tenant, intent: 'CONTRACT.QUERY', mode: 'DIRECT', 'definition-version': 1, 'tool-name': 'contract_fixture',
                    'tool-version': '1', predecessor: 'ORDER.QUERY', parameters: { '[contractNumber]': 'result.contractId' } },
                { tenant, intent: 'CALENDAR.LIST', mode: 'WORKFLOW', 'definition-version': 1, 'skill-key': 'calendar.list' }
            ],
            knowledge: [{ tenant, 'tool-name': 'knowledge_fixture', 'tool-version': '1' }],
            planners: [{ tenant, intent: 'FINANCE.REPORT', 'definition-version': 1, policy: {
                goal: '生成财务报表', tools: ['finance_lookup', 'finance_summary', 'finance_denied'],
                'required-fields': ['answer'], 'max-nodes': 3, 'max-iterations': 2,
                'business-checks': [
                    { id: 'periodMatch', field: 'reportPeriod', operator: 'MATCH_INPUT', 'input-field': 'period' },
                    { id: 'evidenceCount', field: 'evidenceCount', operator: 'MIN_NUMBER', minimum: 2 }
                ],
                subagents: [{ name: 'regional', tool: 'finance_lookup' }, { name: 'control', tool: 'finance_check' },
                    { name: 'optional_check', tool: 'finance_optional', resilience: {
                        'timeout-seconds': 15, 'max-retries': 1, 'on-failure': 'DEGRADE',
                        fallback: { '[verification]': '总账待核验' }
                    } },
                    { name: 'optional_drop', tool: 'finance_optional', resilience: { 'on-failure': 'DROP' } }]
            } }, { tenant, intent: 'PROJECT.RETROSPECTIVE', 'definition-version': 1,
                'workflow-trigger': '再生成管理汇总', policy: {
                    goal: '先运行已绑定的项目复盘，再把 Markdown 交给管理汇总工具', tools: ['retro_summary_fixture'],
                    'required-fields': ['summary'], 'max-nodes': 5, 'max-iterations': 1
                }
            }]
        },
        retrospective: { repositories: [
            { tenant, repository: 'fixture-org/crm-rewrite', ref: 'main', aliases: ['CRM系统重构'], roles: ['runtime-tester'] },
            { tenant, repository: 'fixture-org/crm-archive', ref: 'main', aliases: ['CRM集合'], roles: ['runtime-tester'] },
            { tenant, repository: 'fixture-org/crm-rewrite', ref: 'main', aliases: ['CRM集合'], roles: ['runtime-tester'] }
        ] } }
}, spring: { data: { redis: { database: Number(process.env.ENTERPRISE_RUNTIME_TEST_REDIS_DB ?? 13) } } } });

const processes = [];
function start(worker) {
    const args = ['-jar', jar, '--spring.profiles.active=storage,gateway,runtime,intent,memory-loop' + (worker ? ',worker' : ''),
        '--server.address=127.0.0.1', '--server.port=0', '--spring.main.banner-mode=off'];
    const child = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java', args,
        { env: appEnv, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const app = { child, closed: once(child, 'close'), logs: '', worker };
    processes.push(app);
    app.ready = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(Error('Application failed to start')), 60_000);
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
const skillPublisher = bearer('skills.admin', 'skill-publisher');
const conversationOwner = bearer('intents.submit intents.read runs.submit runs.read tools.invoke');
const intentOnly = bearer('intents.submit intents.read');
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

async function registerConversationTool(name, targetRef, argument, output, additionalOutput = {}) {
    const tool = '/v1/admin/tools/' + name;
    await api('POST', '/v1/admin/tools', 201, admin, { name, description: name, riskLevel: 'LOW' });
    await api('POST', tool + '/versions', 201, admin, {
        version: '1', protocol: 'HTTP', targetRef, timeoutMs: 3000,
        inputSchema: { type: 'object', properties: { [argument]: { type: 'string' },
                ...(name === 'finance_summary' ? { verification: { type: 'string' } } : {}) },
            required: [argument], additionalProperties: false }, outputSchema: {
                type: 'object', properties: { [output]: {}, ...additionalOutput }, required: [output] }
    });
    await api('POST', tool + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
    await api('PUT', tool + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'ALLOW' });
    return tool;
}

async function memoryLoopFlow() {
    const memoryActor = bearer('intents.submit intents.read runs.read memory.profile.write memory.knowledge.read memory.knowledge.write');
    const { conversationId } = await api('POST', '/v1/conversations', 201, memoryActor);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    await api('PUT', '/v1/memory/profile/reportStyle', 204, memoryActor, { value: 'brief', revision: 1 });
    await api('PUT', '/v1/memory/profile/reportStyle', 409, memoryActor, { value: 'detailed', revision: 1 });
    async function ask(text, key, token = memoryActor) {
        const turn = await api('POST', turns, 200, token, { text }, key);
        const path = turns + '/' + turn.turnId;
        for (let attempt = 0; attempt < 30; attempt++) {
            const execution = (await api('GET', path, 200, token)).executions[0];
            if (['SUCCEEDED', 'FAILED', 'BLOCKED'].includes(execution?.status)) return { execution, path };
            await sleep(250);
        }
        assert.fail('memory route did not settle');
    }
    await ask('你好', 'profile-chat');
    assert.ok(preferenceSeen);
    const doc = '/v1/memory/knowledge/' + randomUUID() + '/versions/';
    await api('PUT', doc + '1', 403, intentOnly, { sourceKey: 'refund-policy', chunks: ['内部退款政策第一版。'] });
    await api('PUT', doc + '1', 204, memoryActor, { sourceKey: 'refund-policy', chunks: ['内部退款政策第一版。'] });
    let first;
    for (let attempt = 0; attempt < 24; attempt++) {
        first = await ask('退款政策是什么？', 'indexed-doc-' + attempt);
        if (first.execution.status === 'SUCCEEDED') break;
        await sleep(750);
    }
    assert.equal(first.execution.status, 'SUCCEEDED');
    assert.equal(first.execution.output.answer, '内部退款政策第一版。');
    assert.equal(first.execution.output.sources[0].version, 1);
    const noScope = (await api('GET', first.path, 200, intentOnly)).executions[0];
    assert.equal(noScope.reason, 'MISSING_KNOWLEDGE_SCOPE');
    await api('PUT', doc + '2', 204, memoryActor, { sourceKey: 'refund-policy', chunks: ['内部退款政策第二版。'] });
    assert.equal((await api('GET', first.path, 200, memoryActor)).executions[0].reason, 'KNOWLEDGE_VERSION_UNAVAILABLE');
    let remembered;
    for (let attempt = 0; attempt < 24; attempt++) {
        const turn = await api('POST', turns, 200, memoryActor, { text: '分析上季度财务报表' }, 'episode-recall-' + attempt);
        const trace = await api('GET', turns + '/' + turn.turnId + '/artifacts', 200, memoryActor);
        remembered = trace.candidates[0].stages.find(stage => stage.artifact === 'ContextSnapshot').data.memory;
        if (remembered.episodeIds.length) break;
        await sleep(500);
    }
    assert.deepEqual(remembered.profileKeys, ['reportStyle']);
    assert.ok(remembered.episodeIds.length, 'expected a prior successful Run in memory');
    console.log('Memory loop passed own profile, episode capture and recall, local RAG citations and stale version rejection');
}

async function historyFlow() {
    const token = bearer('intents.submit intents.read', 'history-reader');
    const foreign = bearer('intents.submit intents.read', 'other-history-reader');
    const first = await api('POST', '/v1/conversations', 201, token);
    const second = await api('POST', '/v1/conversations', 201, token);
    const page = await api('GET', '/v1/conversations?limit=1', 200, token);
    assert.deepEqual(page.items.map(item => item.id), [second.conversationId]);
    const older = await api('GET', '/v1/conversations?limit=1&before=' + page.nextBefore, 200, token);
    assert.deepEqual(older.items.map(item => item.id), [first.conversationId]);
    const path = '/v1/conversations/' + first.conversationId + '/turns';
    const recorded = await api('POST', path, 200, token, { text: '查询订单' }, 'historic-1');
    await api('POST', path, 200, token, { text: '你好' }, 'historic-2');
    const latest = await api('GET', path + '?limit=1', 200, token);
    assert.equal(latest.items[0].text, '你好');
    assert.equal(latest.items[0].number, 2);
    const prior = await api('GET', path + '?limit=1&before=' + latest.nextBefore, 200, token);
    assert.equal(prior.items[0].receipt.turnId, recorded.turnId);
    await api('GET', path, 404, foreign);
    await api('POST', path, 200, token, { text: '清空上下文' }, 'historic-clear');
    assert.deepEqual((await api('GET', path, 200, token)).items.map(item => item.number), [3]);
    console.log('History passed pagination, restore, owner isolation, and redacted turns');
}

async function operationsFlow(uncertainRun) {
    const path = '/v1/admin/operations';
    const operator = bearer('ops.read ops.write', 'operator');
    const readOnly = bearer('ops.read', 'new-operator');
    const previousCalls = submits;
    await api('GET', path + '/outbox', 403, admin);
    const dead = await api('GET', path + '/outbox', 200, operator);
    assert.ok(Array.isArray(dead));
    const published = await api('GET', path + '/outbox?dead=false', 200, operator);
    assert.ok(published.length);
    const replay = path + '/outbox/' + published[0].eventId + '/replay';
    await api('POST', replay, 403, readOnly, { reason: '测试无写权限' });
    await api('POST', replay, 404, bearer('ops.write', 'operator', 'isolated'), { reason: '测试跨租户' });
    await api('POST', replay, 202, operator, { reason: '验证原事件重投' });
    const pending = await api('GET', path + '/reconciliation', 200, operator);
    assert.ok(pending.some(run => run.runId === uncertainRun));
    const close = path + '/reconciliation/' + uncertainRun + '/close';
    await api('POST', close, 400, operator, { reason: ' ' });
    await api('POST', close, 204, operator, { reason: '模拟外部核对完成，关闭本次运行' });
    assert.equal((await until(uncertainRun, 'FAILED')).reason, 'RECONCILED_CLOSED');
    await api('POST', close, 409, operator, { reason: '重复关闭' });
    assert.equal(submits, previousCalls);
    console.log('Operations passed tenant and write scopes, event replay and audited reconciliation without resubmission');
}

async function predefinedRuleFlow() {
    const publisher = bearer('rules.admin', 'rule-publisher');
    const path = '/v1/admin/rules/ORDER.QUERY/1';
    const first = {
        parameters: { type: 'object', required: ['orderId'], properties: { orderId: { type: 'string' } } },
        clarification: '请输入该区域的订单编号。',
        safetyBoundary: { allowedRoles: ['restricted-role'], dataScope: 'TENANT', approval: 'INHERIT',
            effect: 'READ_ONLY', constraints: '库内只读查询' }
    };
    await api('PUT', path + '/versions/1', 403, admin, first);
    await api('PUT', path + '/versions/1', 204, publisher, first);
    assert.equal((await api('GET', path, 200, publisher))[0].status, 'DRAFT');
    await api('POST', path + '/versions/1/publish', 204, publisher);
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    const ask = await api('POST', turns, 200, conversationOwner, { text: '查询订单' }, 'rule-question');
    assert.equal(ask.reply, first.clarification);
    const denied = await api('POST', turns, 200, conversationOwner, { text: '查询订单 SO001' }, 'rule-denied');
    assert.equal(denied.executions[0].reason, 'RULE_ROLE_DENIED');
    const revised = structuredClone(first);
    revised.parameters.properties.orderId.pattern = '^SO001$';
    revised.safetyBoundary.allowedRoles = ['runtime-tester'];
    await api('PUT', path + '/versions/2', 204, publisher, revised);
    await api('POST', path + '/versions/2/publish', 204, publisher);
    const versions = await api('GET', path, 200, publisher);
    assert.deepEqual(versions.map(item => item.status), ['PUBLISHED', 'RETIRED']);
    const invalid = await api('POST', turns, 200, conversationOwner, { text: '查询订单 SO444' }, 'rule-invalid');
    assert.equal(invalid.executions[0].reason, 'RULE_PARAMETERS_INVALID');
    const passed = await api('POST', turns, 200, conversationOwner, { text: '查询订单 SO001' }, 'rule-allowed');
    assert.ok(passed.executions[0].targetId);
    const trace = await api('GET', turns + '/' + passed.turnId + '/artifacts', 200, conversationOwner);
    const route = trace.candidates[0].stages.find(stage => stage.artifact === 'RouteDecision');
    assert.equal(route.data.policyVersion, 'predefined-rule-v2');
    console.log('Predefined rules passed save, publish, clarification, role scope, schema and version tracing');
}

async function generalPlannerFlow() {
    for (const [name, target, input, output] of [
        ['finance_lookup', 'finance-lookup', 'period', 'evidence'],
        ['finance_summary', 'finance-summary', 'evidence', 'answer'],
        ['finance_denied', 'finance-lookup', 'period', 'evidence']
    ]) {
        const path = await registerConversationTool(name, target, input, output, name === 'finance_summary'
            ? { reportPeriod: { type: 'string' }, evidenceCount: { type: 'integer' } } : {});
        await api('PUT', path + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'DISCOVER', effect: 'ALLOW' });
        if (name === 'finance_denied') await api('PUT', path + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'DENY' });
    }
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    const body = { text: '分析上季度财务报表' };
    const turn = await api('POST', turns, 200, conversationOwner, body, 'finance-open');
    assert.equal(turn.outcomes[0].intent, 'FINANCE.REPORT', JSON.stringify(turn));
    const run = turn.executions[0];
    assert.equal(run.mode, 'PLAN_AND_EXECUTE');
    assert.ok(run.targetId, JSON.stringify(run));
    const completed = await until(run.targetId, 'SUCCEEDED');
    assert.equal(completed.planVersion, 3);
    assert.deepEqual(await api('GET', completed.resultUrl, 200),
        { answer: '上季度回款同比持平', reportPeriod: '上季度', evidenceCount: 2 });
    assert.equal(financeSummaries, 2);
    assert.deepEqual(generalHistory.map(context => context.missing), [[], ['business.evidenceCount', 'business.periodMatch']]);
    assert.deepEqual(generalHistory[1].failures,
        { 'business.evidenceCount': 'BELOW_MINIMUM', 'business.periodMatch': 'INPUT_VALUE_MISMATCH' });
    assert.deepEqual(generalHistory[0].inputFields, ['period']);
    assert.deepEqual(generalHistory[0].tools.map(tool => tool.name).sort(), ['finance_lookup', 'finance_summary']);
    const path = turns + '/' + turn.turnId;
    const trace = await api('GET', path + '/artifacts', 200, conversationOwner);
    const stages = trace.candidates[0].stages;
    const plan = stages.find(stage => stage.artifact === 'PlanVersion').data;
    assert.equal(plan.nodes.length, 3);
    assert.ok(stages.some(stage => stage.artifact === 'PlanProposal'));
    const state = stages.find(stage => stage.artifact === 'StateUpdate').data;
    assert.deepEqual(state.gateChecks.map(check => check.verdict),
        ['PASS', 'RETRYABLE_FAIL', 'RETRYABLE_FAIL', 'PASS', 'PASS', 'PASS']);
    assert.equal(state.gateChecks.find(check => check.check === 'business.periodMatch').reason, 'INPUT_VALUE_MISMATCH');
    assert.equal((await api('GET', path, 200, conversationOwner)).executions[0].targetId, run.targetId);
    for (const [variant, reason] of [['denied', 'PLANNER_TOOL_DENIED'], ['cycle', 'PLANNER_CYCLE']]) {
        generalVariant = variant;
        const rejectedConversation = await api('POST', '/v1/conversations', 201, conversationOwner);
        const rejected = await api('POST', '/v1/conversations/' + rejectedConversation.conversationId + '/turns',
            200, conversationOwner, body, 'finance-' + variant);
        assert.equal((await until(rejected.executions[0].targetId, 'FAILED')).reason, reason);
    }
    assert.equal(financeSummaries, 2);
    generalVariant = 'wrong-report';
    const wrong = await api('POST', turns, 200, conversationOwner, body, 'finance-permanent-mismatch');
    const exhausted = await until(wrong.executions[0].targetId, 'FAILED');
    assert.equal(exhausted.reason, 'HARD_GATE_BUDGET_EXHAUSTED');
    await api('GET', endpoint(exhausted.runId) + '/result', 404);
    generalVariant = 'valid';
    console.log('General Planner passed configured business checks, bounded replan and denied unauthorized tools and cycles');
}

async function subagentFlow() {
    const control = await registerConversationTool('finance_check', 'finance-check', 'period', 'verification');
    for (const action of ['DISCOVER', 'APPROVE']) {
        await api('PUT', control + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action, effect: 'ALLOW' });
    }
    await api('PUT', control + '/policies/APPROVAL', 200, admin,
        { expectedVersion: 0, rule: { ttlSeconds: 180 } });
    generalVariant = 'delegate';
    const request = { text: '分析上季度财务报表' };
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    async function submit(key) {
        const turn = await api('POST', turns, 200, conversationOwner, request, key);
        const parent = turn.executions[0].targetId;
        const progress = await sse(parent, 0, event => event.type === 'run.child_started'
            && event.data.subagent === 'control');
        const child = progress.at(-1).data.childRunId;
        await until(child, 'WAITING_APPROVAL');
        return { turn, parent, child };
    }
    const first = await submit('delegate-approve');
    const tracePath = turns + '/' + first.turn.turnId + '/artifacts';
    const checkpoint = await api('GET', tracePath, 200, conversationOwner);
    const nodes = checkpoint.candidates[0].stages.find(stage => stage.artifact === 'PlanVersion').data.nodes;
    const children = nodes.filter(node => node.kind === 'SUBAGENT');
    assert.equal(children.length, 2);
    assert.ok(children.every(node => node.childRunId && node.childRunId !== first.parent));
    assert.deepEqual(nodes.find(node => node.key === 'summary').dependsOn, ['check', 'evidence']);
    await api('GET', endpoint(first.child), 404, bearer('runs.read', 'unrelated'));
    const review = await sse(first.child, 0, event => !!event.data.approvalId);
    await api('POST', '/v1/tools/approvals/' + review.at(-1).data.approvalId + '/decisions',
        200, reviewer, { decision: 'APPROVE' });
    await until(first.parent, 'SUCCEEDED');
    assert.deepEqual(await api('GET', endpoint(first.child) + '/result', 200), { verification: '已核对总账' });
    const history = await sse(first.parent, 0);
    assert.equal(history.filter(event => event.type === 'run.child_started').length, 2);
    const cancelled = await submit('delegate-cancel');
    await api('POST', endpoint(cancelled.parent) + '/cancel', 202, owner, undefined, 'cancel-children');
    await until(cancelled.child, 'CANCELLED');
    await until(cancelled.parent, 'CANCELLED');
    generalVariant = 'unknown-subagent';
    const unknown = await api('POST', turns, 200, conversationOwner, request, 'delegate-denied');
    assert.equal((await until(unknown.executions[0].targetId, 'FAILED')).reason, 'PLANNER_SUBAGENT_DENIED');
    generalVariant = 'valid';
    await api('PUT', control + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'DENY' });
    await api('GET', endpoint(first.parent) + '/result', 403);
    console.log('SubAgent passed parallel child Runs, approval, parent cancellation, executor bounds and revoked results');
}

async function resilientSubagentFlow() {
    const control = await registerConversationTool('finance_optional', 'finance-optional', 'period', 'verification');
    await api('PUT', control + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'DISCOVER', effect: 'ALLOW' });
    async function submit(variant) {
        generalVariant = variant;
        const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
        const turns = '/v1/conversations/' + conversationId + '/turns';
        const turn = await api('POST', turns, 200, conversationOwner,
            { text: '分析上季度财务报表' }, 'resilience-' + variant);
        return { run: turn.executions[0].targetId, path: turns + '/' + turn.turnId + '/artifacts' };
    }
    async function checkNode(path) {
        const artifacts = await api('GET', path, 200, conversationOwner);
        return artifacts.candidates[0].stages.find(stage => stage.artifact === 'PlanVersion').data.nodes
            .find(node => node.key === 'check');
    }
    const repaired = await submit('retry-success');
    await until(repaired.run, 'SUCCEEDED');
    assert.deepEqual((await checkNode(repaired.path)).childAttempts.map(item => item.status), ['FAILED', 'SUCCEEDED']);
    const replays = await sse(repaired.run, 0);
    assert.ok(replays.some(event => event.data.reason === 'SUBAGENT_RETRY:OUTPUT_SCHEMA_INVALID'));
    assert.equal(optionalExecutions, 2);
    const degraded = await submit('fallback');
    const completed = await until(degraded.run, 'SUCCEEDED');
    assert.match((await api('GET', completed.resultUrl, 200)).answer, /总账待核验/);
    const degradedNode = await checkNode(degraded.path);
    assert.equal(degradedNode.disposition, 'SUBAGENT_DEGRADED:OUTPUT_SCHEMA_INVALID');
    assert.deepEqual(degradedNode.childAttempts.map(item => item.status), ['FAILED', 'FAILED']);
    assert.equal(optionalExecutions, 4);
    const discarded = await submit('drop');
    await until(discarded.run, 'SUCCEEDED');
    assert.equal((await checkNode(discarded.path)).disposition, 'SUBAGENT_DROPPED:OUTPUT_SCHEMA_INVALID');
    assert.equal(optionalExecutions, 5);
    const invalid = await submit('invalid-drop-data');
    assert.equal((await until(invalid.run, 'FAILED')).reason, 'PLANNER_SUBAGENT_DROP_DEPENDENCY');
    assert.equal(optionalExecutions, 5);
    const unknown = await submit('unknown-effect');
    await until(unknown.run, 'FAILED');
    const uncertainNode = await checkNode(unknown.path);
    assert.equal(uncertainNode.disposition, 'SUBAGENT_NEEDS_RECONCILIATION');
    assert.deepEqual(uncertainNode.childAttempts.map(item => item.status), ['NEEDS_RECONCILIATION']);
    assert.equal(optionalExecutions, 6);
    generalVariant = 'valid';
    console.log('SubAgent resilience passed retry, fallback, order-only drop, unsafe draft rejection and unknown-effect isolation');
}

async function skillPublication() {
    const source = await readFile(new URL('../skills/github-project-retrospective/SKILL.md', import.meta.url), 'utf8');
    const path = '/v1/admin/workflow-skills/github.project-retrospective';
    const lookup = '/v1/admin/workflow-skills/resolve?command=' + encodeURIComponent('/github-retro');
    const release = (version, markdown, expectedRevision, status, token = skillPublisher) =>
        api('POST', path + '/versions/' + version + '/publish', status, token, { markdown, expectedRevision });
    await release(1, source, 0, 403, admin);
    await release(1, source, 0, 409);
    await api('GET', path, 404, skillPublisher);

    for (const [name, fields] of [['github_search_issues', ['repository', 'from', 'to']],
        ['git_log', ['repository', 'ref', 'from', 'to']]]) {
        const tool = '/v1/admin/tools/' + name;
        await api('POST', '/v1/admin/tools', 201, admin, { name, description: 'Evidence contract fixture', riskLevel: 'LOW' });
        await api('POST', tool + '/versions', 201, admin, {
            version: '1', protocol: 'HTTP', targetRef: name === 'github_search_issues' ? 'retro-issues' : 'retro-commits', timeoutMs: 3000,
            inputSchema: { type: 'object', required: fields, additionalProperties: false,
                properties: Object.fromEntries(fields.map(field => [field, {type: 'string'}])) },
            outputSchema: { type: 'object', required: ['complete', 'items'],
                properties: { complete: {type: 'boolean'}, items: {type: 'array'} } }
        });
        await api('POST', tool + '/publish', 200, admin, {version: '1', expectedRevision: 0});
        await api('PUT', tool + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'ALLOW' });
    }
    await api('PUT', '/v1/admin/tools/github_search_issues/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'APPROVE', effect: 'ALLOW' });
    await api('PUT', '/v1/admin/tools/github_search_issues/policies/APPROVAL', 200, admin,
        { expectedVersion: 0, rule: { ttlSeconds: 180 } });
    const cycle = source.replace('"dependsOn": []', '"dependsOn": ["report"]');
    await release(1, cycle, 0, 400);
    const brokenRef = source.replace('context.repository', 'context.token');
    await release(1, brokenRef, 0, 400);
    const first = await release(1, source, 0, 200);
    assert.equal(first.revision, 1);
    assert.equal(first.current, true);
    assert.equal(first.status, 'PUBLISHED');
    assert.deepEqual(first.dag.nodes.map(node => node.id), ['issues', 'commits', 'findings', 'report']);
    assert.match(first.dag.toolBindings.issues.versionId, /^[0-9a-f-]{36}$/);
    assert.equal(first.dag.toolBindings.commits.toolName, 'git_log');
    assert.equal((await release(1, source, 0, 200)).id, first.id);
    await release(1, source + '\n改动', 1, 409);
    assert.equal((await api('GET', lookup, 200, skillPublisher)).id, first.id);
    await api('GET', path, 404, bearer('skills.admin', 'elsewhere', 'other-tenant'));

    const fixture = retroFixture;
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    const exact = await api('POST', turns, 200, conversationOwner, { text: fixture.userInput }, 'crm-retro');
    const intent = exact.outcomes[0];
    assert.equal(exact.outcomes.length, 1);
    assert.equal(intent.intent, 'PROJECT.RETROSPECTIVE');
    assert.equal(intent.status, 'READY');
    assert.equal(intent.route, 'WORKFLOW');
    assert.equal(intent.workflowSkill.id, first.id);
    assert.deepEqual(intent.ir.arguments, { project: fixture.project, days: 30 });
    assert.equal(intent.ir.workflowContext.repository, fixture.repository);
    assert.equal(intent.ir.provenance.repository, 'VERIFIED_LOOKUP');
    exactWorkflow = { runId: exact.executions[0].targetId, path: turns + '/' + exact.turnId };
    assert.equal(exact.executions[0].mode, 'WORKFLOW');
    assert.ok(exactWorkflow.runId);
    const trace = await api('GET', turns + '/' + exact.turnId + '/artifacts', 200, conversationOwner);
    assert.equal(trace.candidates[0].stages.find(item => item.artifact === 'WorkflowSkill').data.id, first.id);
    assert.equal(trace.candidates[0].stages.find(item => item.artifact === 'RouteDecision').data.mode, 'WORKFLOW');

    const unresolved = await api('POST', turns, 200, conversationOwner,
        { text: '帮我做「CRM集合」项目复盘' }, 'crm-ambiguous');
    const pending = unresolved.outcomes[0];
    assert.equal(pending.status, 'NEEDS_CLARIFICATION');
    assert.deepEqual(pending.missing, ['repository']);
    assert.equal(pending.slotProvenance.days, 'SCHEMA_DEFAULT');
    assert.equal(pending.workflowSkill.id, first.id);
    const rejectedRange = await api('POST', turns, 200, conversationOwner,
        { text: '帮我做「CRM系统重构」项目最近1000天的复盘' }, 'crm-invalid-period');
    assert.deepEqual(rejectedRange.outcomes[0].invalid, ['days']);

    const secondSource = source.replace('"version": 1', '"version": 2');
    await release(2, secondSource, 0, 409);
    const second = await release(2, secondSource, 1, 200);
    assert.equal(second.revision, 2);
    assert.equal((await api('GET', lookup, 200, skillPublisher)).id, second.id);
    const historic = await api('GET', path + '?version=1', 200, skillPublisher);
    assert.equal(historic.status, 'RETIRED');
    assert.equal(historic.id, first.id);
    assert.deepEqual(historic.dag, first.dag);
    const continued = await api('POST', turns, 200, conversationOwner,
        { text: 'fixture-org/crm-rewrite', replyToMessageId: unresolved.assistantMessageId }, 'crm-pinned');
    const resumed = continued.outcomes[0];
    assert.equal(resumed.taskId, pending.taskId);
    assert.equal(resumed.status, 'READY');
    assert.equal(resumed.ir.inputVersion, 2);
    assert.equal(resumed.ir.workflowSkill.id, first.id);
    assert.equal(resumed.ir.workflowContext.to, pending.slots.to);
    assert.equal(resumed.ir.arguments.project, 'CRM集合');
    const command = await api('POST', turns, 200, conversationOwner,
        { text: '/github-retro CRM系统重构' }, 'crm-explicit');
    assert.equal(command.outcomes[0].workflowSkill.id, second.id);
    const elsewhere = bearer('intents.submit intents.read', 'elsewhere', 'other-tenant');
    const isolated = await api('POST', '/v1/conversations', 201, elsewhere);
    const unavailable = await api('POST', '/v1/conversations/' + isolated.conversationId + '/turns',
        200, elsewhere, { text: '/github-retro CRM系统重构' }, 'crm-isolation');
    assert.equal(unavailable.outcomes[0].reason, 'SKILL_VERSION_UNAVAILABLE');
    console.log('Workflow Skill passed compile, authorization, publication, trigger lookup and pinned historical version');
    console.log('CRM intent passed natural and command entry, repository clarification, frozen time and pinned Skill through promotion');
}

async function crmFixedWorkflow() {
    await until(exactWorkflow.runId, 'WAITING_APPROVAL');
    const timeline = await sse(exactWorkflow.runId, 0, event => !!event.data.approvalId);
    const approval = timeline.at(-1).data.approvalId;
    assert.ok(approval);
    assert.equal(issueReads, 0);
    await api('POST', '/v1/tools/approvals/' + approval + '/decisions', 200, reviewer, {decision: 'APPROVE'});
    await until(exactWorkflow.runId, 'SUCCEEDED');
    const turn = await api('GET', exactWorkflow.path, 200, conversationOwner);
    assert.equal(turn.executions[0].status, 'SUCCEEDED');
    const report = await api('GET', turn.executions[0].resultUrl, 200, conversationOwner);
    assert.equal(report.format, 'markdown');
    for (const id of retroFixture.expectedTop3) assert.match(report.markdown, new RegExp('/issues/' + id + '#issuecomment-'));
    assert.doesNotMatch(report.markdown, /4444444/);
    assert.equal(issueReads, 1);
    assert.ok(commitReads >= 1);
    const trace = await api('GET', exactWorkflow.path + '/artifacts', 200, conversationOwner);
    const nodes = trace.candidates[0].stages.find(stage => stage.artifact === 'PlanVersion').data.nodes;
    assert.deepEqual(nodes.find(node => node.key === 'findings').dependsOn.sort(), ['commits', 'issues']);
    console.log('CRM fixed workflow passed parallel evidence, approval suspension, resume and Markdown response');
}

async function crmPlannerWorkflow() {
    const tool = await registerConversationTool('retro_summary_fixture', 'retro-summary', 'markdown', 'summary');
    await api('PUT', tool + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'DISCOVER', effect: 'ALLOW' });
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    const pending = await api('POST', turns, 200, conversationOwner,
        { text: '帮我复盘最近一个月的项目再生成管理汇总' }, 'retro-compose-ask');
    assert.equal(pending.outcomes[0].status, 'NEEDS_CLARIFICATION');
    const reply = await api('POST', turns, 200, conversationOwner,
        { text: 'CRM系统重构', replyToMessageId: pending.assistantMessageId }, 'retro-compose-reply');
    assert.equal(reply.outcomes[0].taskId, pending.outcomes[0].taskId);
    assert.equal(reply.outcomes[0].route, 'AGENT', JSON.stringify(reply.outcomes[0]));
    const selection = reply.outcomes[0].ir.workflowSkill;
    const execution = reply.executions[0];
    assert.equal(execution.mode, 'PLAN_AND_EXECUTE');
    await until(execution.targetId, 'WAITING_APPROVAL');
    const stream = await sse(execution.targetId, 0, event => !!event.data.approvalId);
    assert.equal(compositionSummaries, 0);
    const skillSource = await readFile(new URL('../skills/github-project-retrospective/SKILL.md', import.meta.url), 'utf8');
    const release = await api('POST', '/v1/admin/workflow-skills/github.project-retrospective/versions/3/publish',
        200, skillPublisher, { expectedRevision: 2, markdown: skillSource.replace('"version": 1', '"version": 3') });
    assert.notEqual(release.id, selection.id);
    await api('POST', '/v1/tools/approvals/' + stream.at(-1).data.approvalId + '/decisions',
        200, reviewer, { decision: 'APPROVE' });
    await until(execution.targetId, 'SUCCEEDED');
    assert.equal(compositionSummaries, 1);
    assert.equal(compositionContexts[0].skill.id, selection.id);
    const path = turns + '/' + reply.turnId;
    const saved = await api('GET', path, 200, conversationOwner);
    assert.deepEqual(await api('GET', saved.executions[0].resultUrl, 200, conversationOwner),
        { summary: '已从带出处的复盘报告整理改进事项' });
    const trace = await api('GET', path + '/artifacts', 200, conversationOwner);
    const nodes = trace.candidates[0].stages.find(stage => stage.artifact === 'PlanVersion').data.nodes;
    const inner = nodes.filter(node => node.skillVersion);
    assert.equal(inner.length, 4);
    assert.ok(inner.every(node => node.skillVersion.id === selection.id && node.skillVersion.version === selection.version));
    assert.deepEqual(nodes.find(node => node.skillNode === 'findings').dependsOn.sort(), ['_retro--commits', '_retro--issues']);
    assert.deepEqual(nodes.find(node => node.key === 'summary').dependsOn, ['retro']);
    compositionOmitSkill = true;
    const item = await api('POST', '/v1/conversations', 201, conversationOwner);
    const rejected = await api('POST', '/v1/conversations/' + item.conversationId + '/turns', 200, conversationOwner,
        { text: '帮我复盘最近一个月的「CRM系统重构」项目再生成管理汇总' }, 'retro-compose-invalid-plan');
    assert.equal((await until(rejected.executions[0].targetId, 'FAILED')).reason, 'PLANNER_SKILL_REQUIRED');
    assert.equal(compositionSummaries, 1);
    console.log('Planner composed pinned CRM SOP, resumed approval, passed Markdown downstream and rejected omitted Skill');
}

async function refundFixedWorkflow() {
    const tools = [
        { name: 'refunds_eligibility', targetRef: 'refund-eligible', riskLevel: 'LOW', inputs: ['orderId'], outputs: {refundable: 'boolean'} },
        { name: 'refunds_submit', targetRef: 'refund-submit', riskLevel: 'HIGH', inputs: ['orderId', 'reason'],
            outputs: {refundId: 'string', status: 'string'} }
    ];
    for (const tool of tools) {
        const path = '/v1/admin/tools/' + tool.name;
        const schema = fields => ({ type: 'object', required: Object.keys(fields),
            properties: Object.fromEntries(Object.entries(fields).map(([name, type]) => [name, {type}])) });
        await api('POST', '/v1/admin/tools', 201, admin, {name: tool.name, description: 'Refund test fixture', riskLevel: tool.riskLevel});
        await api('POST', path + '/versions', 201, admin, {version: '1', protocol: 'HTTP', targetRef: tool.targetRef, timeoutMs: 3000,
            inputSchema: schema(Object.fromEntries(tool.inputs.map(key => [key, 'string']))), outputSchema: schema(tool.outputs)});
        await api('POST', path + '/publish', 200, admin, {version: '1', expectedRevision: 0});
        await api('PUT', path + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'ALLOW' });
    }
    await api('PUT', '/v1/admin/tools/refunds_submit/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'APPROVE', effect: 'ALLOW' });
    await api('PUT', '/v1/admin/tools/refunds_submit/policies/APPROVAL', 200, admin,
        { expectedVersion: 0, rule: {ttlSeconds: 180} });
    const markdown = await readFile(new URL('../skills/order-refund/SKILL.md', import.meta.url), 'utf8');
    await api('POST', '/v1/admin/workflow-skills/order.refund/versions/1/publish', 200, skillPublisher,
        { expectedRevision: 0, markdown });

    const requester = bearer('intents.submit intents.read runs.submit runs.read tools.invoke refunds.submit');
    const { conversationId } = await api('POST', '/v1/conversations', 201, requester);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    const denied = await api('POST', turns, 200, conversationOwner, { text: '/refund SO001' }, 'refund-no-scope');
    assert.equal(denied.outcomes[0].status, 'DENIED');
    const ask = await api('POST', turns, 200, requester, { text: '/refund SO001' }, 'refund-order');
    assert.deepEqual(ask.outcomes[0].missing, ['reason']);
    assert.equal(ask.executions.length, 0);
    const answer = { text: '因为重复下单', replyToMessageId: ask.assistantMessageId };
    const accepted = await api('POST', turns, 200, requester, answer, 'refund-reason');
    assert.equal(accepted.outcomes[0].taskId, ask.outcomes[0].taskId);
    const runId = accepted.executions[0].targetId;
    assert.equal((await api('POST', turns, 200, requester, answer, 'refund-reason')).executions[0].targetId, runId);
    await until(runId, 'WAITING_APPROVAL');
    const progress = await sse(runId, 0, event => !!event.data.approvalId);
    assert.equal(progress.at(-1).data.node, 'submit_refund');
    assert.equal(refundWrites, 0);
    const approvalPath = '/v1/tools/approvals/' + progress.at(-1).data.approvalId + '/decisions';
    await api('POST', approvalPath, 403, bearer('tools.approve'), {decision: 'APPROVE'});
    await api('POST', approvalPath, 200, reviewer, {decision: 'APPROVE'});
    await until(runId, 'SUCCEEDED');
    const receipt = await api('GET', turns + '/' + accepted.turnId, 200, requester);
    assert.equal(receipt.executions[0].status, 'SUCCEEDED');
    assert.deepEqual(await api('GET', receipt.executions[0].resultUrl, 200, requester),
        { refundId: 'fixture-refund-001', status: 'REFUNDED' });
    assert.equal(refundWrites, 1);
    const blocked = await api('POST', turns, 200, requester, { text: '/refund SO002 原因重复下单' }, 'refund-ineligible');
    await until(blocked.executions[0].targetId, 'FAILED');
    const history = await sse(blocked.executions[0].targetId, 0);
    assert.ok(history.some(event => event.data.reason === 'ORDER_NOT_REFUNDABLE'));
    assert.equal(refundWrites, 1);
    console.log('Refund fixed workflow passed anchored clarification, eligibility, approval, resume, idempotency and result');
}

async function generalRouting() {
    for (const [name, targetRef, properties] of [
        ['contract_fixture', 'route-contract', { contractNumber: {type: 'string'} }],
        ['calendar_fixture', 'route-calendar', {}],
        ['knowledge_fixture', 'route-knowledge', { query: {type: 'string'} }]
    ]) {
        const path = '/v1/admin/tools/' + name;
        await api('POST', '/v1/admin/tools', 201, admin, {name, description:'Routing test fixture', riskLevel:'LOW'});
        await api('POST', path + '/versions', 201, admin, {
            version:'1', protocol:'HTTP', targetRef, timeoutMs:3000, outputSchema:{type:'object'},
            inputSchema:{type:'object', properties, required:Object.keys(properties), additionalProperties:false}
        });
        await api('POST', path + '/publish', 200, admin, {version:'1', expectedRevision:0});
        await api('PUT', path + '/permissions', 204, admin,
            {subjectType:'ROLE', subjectId:'runtime-tester', action:'EXECUTE', effect:'ALLOW'});
    }
    const spec = { skillKey:'calendar.list', version:1, intentDefinition:'CALENDAR.LIST@1',
        executionMode:'WORKFLOW', admissionPolicy:'workflow.read', triggers:[{kind:'COMMAND', key:'/calendar'}],
        inputSchema:{type:'object', properties:{}, required:[], additionalProperties:false},
        nodes:[{id:'calendar', tool:'calendar_fixture@1', dependsOn:[], inputs:{}, output:'GenericResult'}],
        output:'steps.calendar.output' };
    const markdown = '# 日程查询\n\n' + '```json\n' + JSON.stringify(spec) + '\n```\n';
    const skill = await api('POST', '/v1/admin/workflow-skills/calendar.list/versions/1/publish', 200, skillPublisher,
        {expectedRevision:0, markdown});
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    const send = (text, key) => api('POST', turns, 200, conversationOwner, {text}, key);
    async function execution(turn, ordinal, status) {
        let current;
        for (let i = 0; i < 80; i++) {
            const receipt = await api('GET', turns + '/' + turn.turnId, 200, conversationOwner);
            current = receipt.executions.find(item => item.candidateOrdinal === ordinal);
            if (current?.status === status || ['FAILED','BLOCKED'].includes(current?.status)) break;
            await sleep(200);
        }
        assert.equal(current?.status, status, JSON.stringify(current));
        return current;
    }
    const chain = await send('查订单 SO777，然后查合同', 'chain');
    assert.deepEqual(chain.outcomes.map(outcome => outcome.intent), ['ORDER.QUERY', 'CONTRACT.QUERY']);
    const downstream = await execution(chain, 1, 'SUCCEEDED');
    assert.deepEqual(downstream.output, {contractNumber:'CT-031', status:'SIGNED'});
    const artifact = await api('GET', turns + '/' + chain.turnId + '/artifacts', 200, conversationOwner);
    assert.equal(artifact.candidates[1].stages.find(stage => stage.artifact === 'RouteDecision').data.predecessorOrdinal, 0);
    const blocked = await send('查订单 SO002，然后查合同', 'chain-no-field');
    assert.equal((await execution(blocked, 1, 'BLOCKED')).reason, 'BOUND_FIELD_MISSING');
    const missing = await send('查合同', 'chain-no-parent');
    assert.equal(missing.executions[0].reason, 'DEPENDENCY_REQUIRED');
    const workflow = await send('帮我查日程', 'calendar-route');
    assert.equal(workflow.outcomes[0].workflowSkill.id, skill.id);
    const run = await execution(workflow, 0, 'SUCCEEDED');
    assert.deepEqual(await api('GET', run.resultUrl, 200, conversationOwner), {events:['项目周会']});
    const chat = await send('你好', 'chat-answer');
    assert.equal((await execution(chat, 0, 'SUCCEEDED')).output.answer, '你好，需要我帮你处理什么？');
    assert.equal((await send('你好', 'chat-answer')).turnId, chat.turnId);
    assert.equal(chatCalls, 1);
    const knowledge = await send('退款政策是什么？', 'knowledge-answer');
    assert.equal((await execution(knowledge, 0, 'SUCCEEDED')).output.sources[0].id, 'policy-test');
    const withoutTools = bearer('intents.read');
    const hidden = await api('GET', turns + '/' + knowledge.turnId, 200, withoutTools);
    assert.equal(hidden.executions[0].reason, 'MISSING_RESULT_SCOPE');
    assert.equal(hidden.executions[0].output, null);
    console.log('General routing passed result handoff, blocked dependency, configured SOP, Chat LLM and knowledge citations');
}

async function conversationFlows() {
    const orderTool = await registerConversationTool('orders_lookup', 'orders', 'orderId', 'status');
    await api('PUT', orderTool + '/policies/REDACTION', 200, admin,
        { expectedVersion: 0, rule: { remove: ['/privateNote'] } });
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    const ask = await api('POST', turns, 200, conversationOwner, { text: '查一下订单' }, 'ask-order');
    assert.equal(ask.outcomes[0].status, 'NEEDS_CLARIFICATION');
    assert.equal(ask.executions.length, 0);
    const firstPath = turns + '/' + ask.turnId + '/artifacts';
    const firstTrace = await api('GET', firstPath, 200, conversationOwner);
    const artifact = (trace, name) => trace.candidates[0].stages.find(stage => stage.artifact === name);
    assert.equal(firstTrace.intake[1].data.text, '查一下订单');
    assert.equal(firstTrace.intake[0].data.idempotencyKey, 'ask-order');
    assert.deepEqual(artifact(firstTrace, 'SlotState').data.validation.missing, ['orderId']);
    assert.equal(artifact(firstTrace, 'ValidatedIntentIR').status, 'NOT_PRODUCED');
    const answer = { text: 'SO001', replyToMessageId: ask.assistantMessageId };
    const receipts = await Promise.all(Array.from({ length: 4 },
        () => api('POST', turns, 200, conversationOwner, answer, 'order-number')));
    const order = receipts[0];
    assert.equal(order.outcomes[0].taskId, ask.outcomes[0].taskId);
    assert.equal(order.outcomes[0].ir.definitionVersion, 1);
    assert.equal(order.outcomes[0].ir.inputVersion, 2);
    assert.equal(order.outcomes[0].ir.responseMode, 'RESULT');
    assert.equal(order.outcomes[0].ir.riskHint, 'NORMAL');
    assert.ok(receipts.every(r => r.executions[0].targetId === order.executions[0].targetId));
    assert.equal(order.executions[0].mode, 'DIRECT');
    const orderPath = turns + '/' + order.turnId;
    let result;
    for (let attempt = 0; attempt < 40; attempt++) {
        result = (await api('GET', orderPath, 200, conversationOwner)).executions[0];
        if (result.status === 'SUCCEEDED') break;
        await sleep(150);
    }
    assert.equal(result.status, 'SUCCEEDED', JSON.stringify(result));
    assert.deepEqual(result.output, { orderId: 'SO001', status: 'SHIPPED' });
    const orderTrace = await api('GET', orderPath + '/artifacts', 200, conversationOwner);
    assert.equal(orderTrace.intake[1].data.text, 'so001');
    assert.equal(artifact(orderTrace, 'IntentCandidate').data.draft.classifierIntent, 'UNKNOWN');
    assert.equal(artifact(orderTrace, 'ContextRelation').data.targetInputVersion, 1);
    assert.equal(artifact(orderTrace, 'ContextRelation').data.targetTaskId, ask.outcomes[0].taskId);
    assert.equal(artifact(orderTrace, 'ParameterSchema').data.definitionVersion, 1);
    assert.deepEqual(artifact(orderTrace, 'FilledSlots').data.values, { orderId: 'SO001' });
    assert.deepEqual(artifact(orderTrace, 'ValidatedIntentIR').data, order.outcomes[0].ir);
    assert.equal(artifact(orderTrace, 'RouteDecision').data.mode, 'DIRECT');
    assert.equal(artifact(orderTrace, 'ToolCall').data.toolVersion, '1');
    assert.deepEqual(artifact(orderTrace, 'ToolResult').data.output, { orderId: 'SO001', status: 'SHIPPED' });
    assert.equal(artifact(orderTrace, 'StateUpdate').data.status, 'SUCCEEDED');
    assert.equal(orderCalls, 1);
    assert.equal((await api('GET', orderPath, 200, intentOnly)).executions[0].reason, 'MISSING_RESULT_SCOPE');
    await api('GET', orderPath, 404, bearer('intents.read tools.invoke', 'other'));
    await api('GET', orderPath + '/artifacts', 404, bearer('intents.read tools.invoke', 'other'));
    await api('GET', orderPath + '/artifacts', 403, owner);
    const traceWithoutToolScope = await api('GET', orderPath + '/artifacts', 200, intentOnly);
    assert.equal(artifact(traceWithoutToolScope, 'ToolResult'), undefined);
    const blocked = await api('POST', turns, 200, intentOnly, { text: '查询订单 SO002' }, 'no-invoke');
    assert.equal(blocked.outcomes[0].status, 'READY');
    assert.equal(blocked.executions[0].reason, 'MISSING_EXECUTION_SCOPE');
    assert.equal((await api('POST', turns, 200, conversationOwner,
        { text: '查询订单 SO002' }, 'no-invoke')).executions[0].reason, 'MISSING_EXECUTION_SCOPE');
    await api('PUT', orderTool + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'DENY' });
    assert.equal((await api('GET', orderPath, 200, conversationOwner)).executions[0].reason, 'TOOL_FORBIDDEN');
    assert.equal((await api('POST', turns, 200, conversationOwner,
        { text: '查询订单 SO003' }, 'denied')).executions[0].reason, 'TOOL_FORBIDDEN');
    assert.equal(orderCalls, 1);

    const unboundSales = await api('POST', turns, 200, conversationOwner,
        { text: '分析上季度销售下降的原因' }, 'sales-before-tool');
    assert.equal(unboundSales.executions[0].reason, 'RUN_NOT_FOUND');
    await registerConversationTool('sales_conversation', 'sales-sync', 'period', 'rows');
    const salesAsk = await api('POST', turns, 200, conversationOwner,
        { text: '分析销售下降原因' }, 'sales-ask');
    assert.equal(salesAsk.outcomes[0].status, 'NEEDS_CLARIFICATION');
    const sales = await api('POST', turns, 200, conversationOwner,
        { text: '上季度', replyToMessageId: salesAsk.assistantMessageId }, 'sales-period');
    assert.equal(sales.outcomes[0].taskId, salesAsk.outcomes[0].taskId);
    assert.equal(sales.outcomes[0].ir.definitionVersion, 2);
    assert.equal(sales.outcomes[0].ir.inputVersion, 2);
    assert.equal(sales.outcomes[0].ir.responseMode, 'PROGRESS_AND_RESULT');
    const run = sales.executions[0];
    assert.equal(run.mode, 'PLAN_AND_EXECUTE');
    assert.ok(run.eventsUrl.endsWith('/events'));
    await until(run.targetId, 'SUCCEEDED');
    const salesView = (await api('GET', turns + '/' + sales.turnId, 200, conversationOwner)).executions[0];
    assert.equal(salesView.status, 'SUCCEEDED');
    assert.equal((await api('GET', salesView.resultUrl, 200)).totals.change, -10);
    assert.ok((await sse(run.targetId, 0)).some(event => event.type === 'run.gate_evaluated'));
    const salesTrace = await api('GET', turns + '/' + sales.turnId + '/artifacts', 200, conversationOwner);
    assert.ok(artifact(salesTrace, 'PlanProposal').data.some(proposal =>
        proposal.status === 'PUBLISHED' && proposal.validation.valid && proposal.draft.gate));
    assert.ok(artifact(salesTrace, 'PlanVersion').data.nodes.some(node => node.dependsOn.length > 0));
    assert.equal(artifact(salesTrace, 'ToolCall').data.toolName, 'sales_conversation');
    assert.equal(artifact(salesTrace, 'ToolResult').data.status, 'SUCCEEDED');
    const salesState = artifact(salesTrace, 'StateUpdate').data;
    assert.equal(salesState.run.status, 'SUCCEEDED');
    assert.ok(salesState.gateChecks.some(check => check.verdict === 'PASS'));
    if (process.env.INTENT_ARTIFACTS_EXAMPLE) {
        const examples = { firstTurn: firstTrace, secondTurn: orderTrace, salesRun: salesTrace };
        await writeFile(process.env.INTENT_ARTIFACTS_EXAMPLE, JSON.stringify(examples, null, 2) + '\n');
    }
    await api('POST', turns, 200, conversationOwner, { text: '清空上下文' }, 'reset');
    const redacted = await api('GET', firstPath, 200, conversationOwner);
    assert.equal(redacted.status, 'REDACTED');
    assert.deepEqual(redacted.intake, []);
    assert.deepEqual(redacted.candidates, []);
    console.log('Conversation passed slot clarification, durable DIRECT execution, policy denial, redacted replay, Planner handoff and SSE');
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
    await skillPublication();
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
    await crmFixedWorkflow();
    await refundFixedWorkflow();
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
    await conversationFlows();
    await generalPlannerFlow();
    await subagentFlow();
    await resilientSubagentFlow();
    await crmPlannerWorkflow();
    await api('PUT', '/v1/admin/tools/orders_lookup/permissions', 204, admin,
        {subjectType:'ROLE', subjectId:'runtime-tester', action:'EXECUTE', effect:'ALLOW'});
    await generalRouting();
    await memoryLoopFlow();
    await operationsFlow(uncertain.runId);
    await historyFlow();
    await predefinedRuleFlow();
} catch (error) {
    for (const process of processes) {
        const causes = process.logs.split(/\r?\n/).filter(line => /Caused by:|ERROR|### (Cause|SQL):/.test(line));
        console.error('\n--- ' + (process.worker ? 'worker' : 'API') + ' ---\n'
            + (causes.length ? causes.join('\n') : process.logs.slice(-1_200)));
    }
    throw error;
} finally {
    for (const app of processes) {
        if (app.child.exitCode === null && !app.child.killed) app.child.kill();
    }
    await Promise.allSettled(processes.map(app => app.closed));
    fixture.closeAllConnections();
    fixture.close();
}
