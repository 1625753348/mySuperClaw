import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHmac, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
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
const monitorReads = new Map();
const monitorNotices = [];
const monitorRemediations = [];
const monitorSopCalls = [];
const callbackSigningKey = randomBytes(32);
let gatewayFailureCalls = 0;
let skillInputVariant = 'valid';
const boundForecastCalls = [];
const multiSopCalls = [];
let multiSopVariant = 'valid';
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
const chatContexts = [];
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
    if (route.pathname === '/monitor/metric') {
        const { metric } = await bodyOf(req);
        const visits = (monitorReads.get(metric) ?? 0) + 1;
        monitorReads.set(metric, visits);
        const normal = metric === 'sequence' ? visits >= 3 : visits >= 2;
        return res.end(JSON.stringify({ value: normal ? 2 : 20 }));
    }
    if (route.pathname === '/monitor/notify' || route.pathname === '/monitor/remediate') {
        const calls = route.pathname.endsWith('/notify') ? monitorNotices : monitorRemediations;
        calls.push(await bodyOf(req));
        return res.end(JSON.stringify({ accepted: true }));
    }
    if (route.pathname.startsWith('/monitor/sop/')) {
        const phase = route.pathname.split('/').at(-1);
        const input = await bodyOf(req);
        monitorSopCalls.push({ phase, ...input });
        return res.end(JSON.stringify({ review: phase }));
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
            chatContexts.push(JSON.parse(request.messages.find(message => message.role === 'user').content));
            return res.end(JSON.stringify({ id: 'chat-fixture', object: 'chat.completion', created: 1, model: 'fixture',
                choices: [{ index: 0, message: { role: 'assistant', content: '你好，需要我帮你处理什么？' }, finish_reason: 'stop' }] }));
        }
        const context = JSON.parse(request.messages.find(message => message.role === 'user').content);
        if (request.messages.some(message => message.role === 'system' && message.content.includes('general DAG proposal'))) {
            if (context.skills?.[0]?.key === 'finance.evidence') {
                const nodes = [
                    { id: 'read', skill: 'finance.evidence', dependsOn: [], inputs: { period: 'input.period' } },
                    { id: 'publish', skill: 'finance.ticket', dependsOn: ['read'],
                        inputs: { evidence: multiSopVariant === 'invalid-binding'
                            ? 'steps.read.output.unknown' : 'steps.read.output.evidence' } }
                ];
                if (multiSopVariant === 'omit') nodes.pop();
                return res.end(JSON.stringify({ id: 'multi-sop-fixture', object: 'chat.completion', created: 1, model: 'fixture',
                    choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify({ nodes, result: nodes.at(-1).id }) },
                        finish_reason: 'stop' }] }));
            }
            if (context.skill?.key === 'weather.forecast') {
                assert.deepEqual(context.skill.upstreamFields, ['city']);
                const draft = { nodes: [
                    { id: 'normalize', tool: 'weather_city_fixture', dependsOn: [], inputs: { city: 'input.city' } },
                    { id: 'forecast', skill: context.skill.key,
                        dependsOn: skillInputVariant === 'undeclared' ? [] : ['normalize'],
                        inputs: skillInputVariant === 'disallowed'
                            ? { date: 'steps.normalize.output.city' } : { city: 'steps.normalize.output.city' } }
                ], result: 'forecast' };
                return res.end(JSON.stringify({ id: 'bound-skill-fixture', object: 'chat.completion', created: 1, model: 'fixture',
                    choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify(draft) }, finish_reason: 'stop' }] }));
            }
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
    if (route.pathname === '/v1/secret/data/gateway/fixture') {
        if (req.headers['x-vault-token'] !== 'fixture-vault-token') return res.writeHead(403).end('{}');
        return res.end(JSON.stringify({ data: { data: { bearerToken: 'fixture-downstream-token' } } }));
    }
    if (route.pathname === '/gateway/vault-consumer') {
        return req.headers.authorization === 'Bearer fixture-downstream-token'
            ? res.end(JSON.stringify({ result: 'vault-ok' })) : res.writeHead(403).end('{}');
    }
    if (route.pathname === '/gateway/failure') {
        gatewayFailureCalls++;
        return res.writeHead(503).end('{}');
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
    if (route.pathname === '/composition/evidence') {
        const args = await bodyOf(req);
        multiSopCalls.push({ node: 'read', args });
        return res.end(JSON.stringify({ evidence: '上季度证据' }));
    }
    if (route.pathname === '/composition/ticket') {
        const args = await bodyOf(req);
        multiSopCalls.push({ node: 'write', args });
        return res.end(JSON.stringify({ ticketId: 'T-TEST', accepted: multiSopVariant !== 'failed-gate' }));
    }
    if (route.pathname === '/weather/normalize') {
        const { city } = await bodyOf(req);
        if (city !== '北京') return res.writeHead(400).end('{}');
        return res.end(JSON.stringify({ city: skillInputVariant === 'bad-value' ? '不支持的地区' : '上海' }));
    }
    if (route.pathname === '/weather/forecast') {
        const { city } = await bodyOf(req);
        boundForecastCalls.push(city);
        return res.end(JSON.stringify({ forecast: city + '天气晴' }));
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
    if (route.pathname === '/jobs/status' && route.searchParams.get('jobId') === 'callback-report') {
        return res.end(JSON.stringify({ status: 'RUNNING' }));
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
        targets: { orders: issuer + '/orders', 'sales-sync': issuer + '/sales', 'monitor-metric': issuer + '/monitor/metric',
            'monitor-notify': issuer + '/monitor/notify', 'monitor-remediate': issuer + '/monitor/remediate',
            'monitor-sop-gate': issuer + '/monitor/sop/gate', 'monitor-sop-evidence': issuer + '/monitor/sop/evidence',
            'monitor-sop-review': issuer + '/monitor/sop/review',
            'retro-issues': issuer + '/retro/issues', 'retro-commits': issuer + '/retro/commits',
            'refund-eligible': issuer + '/refund/eligible', 'refund-submit': issuer + '/refund/submit',
            'route-contract': issuer + '/route/contract', 'route-calendar': issuer + '/route/calendar',
            'route-knowledge': issuer + '/route/knowledge',
            'finance-lookup': issuer + '/finance/lookup', 'finance-summary': issuer + '/finance/summary',
            'finance-check': issuer + '/finance/check',
            'weather-city': issuer + '/weather/normalize', 'weather-forecast': issuer + '/weather/forecast',
            'finance-optional': issuer + '/finance/optional',
            'composition-evidence': issuer + '/composition/evidence', 'composition-ticket': issuer + '/composition/ticket',
            'retro-summary': issuer + '/retro/summary', 'gateway-failure': issuer + '/gateway/failure',
            'vault-consumer': issuer + '/gateway/vault-consumer' },
        callbacks: { 'fixture-sales': { tenant, 'target-ref': 'sales', 'signing-key': callbackSigningKey.toString('base64') } },
        vault: { endpoint: issuer, token: 'fixture-vault-token', 'allow-local-http': true },
        secrets: { 'fixture-vault': { 'vault-path': 'gateway/fixture', 'allowed-tenants': [tenant],
            'allowed-targets': ['HTTP:vault-consumer'] } },
        'async-targets': { sales: { 'submit-uri': issuer + '/jobs/submit', 'status-uri': issuer + '/jobs/status', 'allowed-tenants': [tenant] } }
    },
    runtime: { 'lease-seconds': 3, planner: { enabled: true, 'base-url': issuer, 'api-key': 'fixture', model: 'fixture' },
        'general-planner': { enabled: true, 'base-url': issuer, 'api-key': 'fixture', model: 'fixture' } },
    monitor: { 'read-only': [{ tenant, tool: 'monitor_metric', version: '1' }],
        notification: [{ tenant, tool: 'monitor_notify', version: '1' }],
        remediation: ['monitor_action', 'monitor_unapproved', 'monitor_sop_gate'].map(tool => ({ tenant, tool, version: '1' })),
        'workflow-remediation': [{ tenant, 'skill-key': 'monitor.alert-review', version: 1,
            'approval-tool': 'monitor_sop_gate', 'approval-version': '1' }] },
    intent: { dispatch: { 'sales-tool': 'sales_conversation' }, model: { enabled: false },
        chat: { enabled: true, 'base-url': issuer, 'api-key': 'fixture', name: 'fixture' },
        routing: {
            bindings: [
                { tenant, intent: 'CONTRACT.QUERY', mode: 'DIRECT', 'definition-version': 1, 'tool-name': 'contract_fixture',
                    'tool-version': '1', predecessor: 'ORDER.QUERY', parameters: { '[contractNumber]': 'result.contractId' } },
                { tenant, intent: 'CALENDAR.LIST', mode: 'WORKFLOW', 'definition-version': 1, 'skill-key': 'calendar.list' },
                { tenant, intent: 'WEATHER.QUERY', mode: 'WORKFLOW', 'definition-version': 1, 'skill-key': 'weather.forecast',
                    fallback: { 'tool-name': 'weather_forecast_fixture', 'tool-version': '1', parameters: { '[city]': 'slot.city' } } }
            ],
            knowledge: [{ tenant, 'tool-name': 'knowledge_fixture', 'tool-version': '1' }],
            planners: [{ tenant, intent: 'FINANCE.REPORT', 'definition-version': 1,
                policy: process.argv.includes('--multi-sop-only') ? {
                    goal: '先获取证据再提交工单', 'required-fields': ['ticketId'], 'max-nodes': 4, 'max-iterations': 1,
                    'business-checks': [{ id: 'accepted', field: 'accepted', operator: 'IS_TRUE' }],
                    'workflow-skills': [
                        { key: 'finance.evidence', version: 1, 'input-fields': ['period'] },
                        { key: 'finance.ticket', version: 1, 'input-fields': ['evidence'], 'allow-write': true }
                    ]
                } : {
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
            }, { tenant, intent: 'WEATHER.QUERY', 'definition-version': 1, 'workflow-trigger': '使用预报核验', policy: {
                goal: '查到服务地区后执行预报 SOP', tools: ['weather_city_fixture'], 'required-fields': ['forecast'],
                'max-nodes': 3, 'max-iterations': 1, 'skill-input-fields': ['city']
            } }]
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
            app.port ??= app.logs.match(/Tomcat started on ports?\s+(\d+)/)?.[1];
            if ((worker || app.port) && /Started EnterpriseAgentApplication/.test(app.logs)) {
                clearTimeout(timer);
                resolve(app.port ? 'http://127.0.0.1:' + app.port : null);
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

async function conversationSummaryFlow() {
    const subject = bearer('intents.submit intents.read');
    const { conversationId } = await api('POST', '/v1/conversations', 201, subject);
    const turns = '/v1/conversations/' + conversationId + '/turns';
    async function chat(text, key, path = turns) {
        const receipt = await api('POST', path, 200, subject, { text }, key);
        for (let attempt = 0; attempt < 40; attempt++) {
            const result = await api('GET', path + '/' + receipt.turnId, 200, subject);
            if (result.executions[0]?.status === 'SUCCEEDED') return result;
            assert.notEqual(result.executions[0]?.status, 'FAILED', result.executions[0]?.reason);
            await sleep(250);
        }
        assert.fail('chat with conversation memory did not settle');
    }
    await chat('你好，海棠花', 'summary-old');
    assert.deepEqual(chatContexts.at(-1).summary, []);
    await api('POST', turns, 200, subject, { text: '查询订单 SO713' }, 'summary-business');
    for (let index = 1; index <= 9; index++) {
        await chat('你好，笑话第' + index + '条', 'summary-chat-' + index);
    }
    const context = chatContexts.at(-1);
    assert.equal(context.currentMessage, '你好，笑话第9条');
    assert.equal(context.summary[0].user, '你好，海棠花');
    assert.equal(context.summary[0].assistant, '你好，需要我帮你处理什么？');
    assert.equal(context.recent.length, 8);
    assert.ok(context.recent.some(turn => turn.user === '你好，笑话第8条'));
    assert.ok(!JSON.stringify(context).includes('SO713'));

    const other = await api('POST', '/v1/conversations', 201, subject);
    const fallback = await chat('你是一个助手啊', 'summary-isolated', '/v1/conversations/' + other.conversationId + '/turns');
    assert.equal(fallback.outcomes[0].reason, 'CONVERSATIONAL_FALLBACK');
    assert.equal(fallback.executions[0].mode, 'CHAT');
    assert.ok(fallback.executions[0].output.answer);
    assert.equal(chatContexts.at(-1).currentMessage, '你是一个助手啊');
    assert.deepEqual(chatContexts.at(-1).summary, []);
    assert.deepEqual(chatContexts.at(-1).recent, []);
    await api('POST', turns, 200, subject, { text: '清空上下文' }, 'summary-clear');
    await chat('你好，清理后', 'summary-after-clear');
    assert.deepEqual(chatContexts.at(-1).summary, []);
    assert.deepEqual(chatContexts.at(-1).recent, []);
    console.log('Conversation summary passed sliding window, completed replies, business isolation and reset');
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

async function gatewayProductionFlow(workerBase) {
    const executions = '/v1/tools/executions';
    const register = async (name, protocol, targetRef, input, output, extra = {}) => {
        const tool = '/v1/admin/tools/' + name;
        await api('POST', '/v1/admin/tools', 201, admin, { name, description: name, riskLevel: 'LOW' });
        await api('POST', tool + '/versions', 201, admin, {
            protocol, targetRef, version: '1', timeoutMs: 3000, ...extra,
            inputSchema: { type: 'object', properties: { [input]: { type: 'string' } }, required: [input] },
            outputSchema: { type: 'object', properties: { [output]: { type: 'string' } }, required: [output] }
        });
        await api('POST', tool + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
        await api('PUT', tool + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'ALLOW' });
        return tool;
    };
    await register('gateway_vault_fixture', 'HTTP', 'vault-consumer', 'input', 'result', { credentialRef: 'fixture-vault' });
    const vault = await api('POST', executions, 200, owner, { toolName: 'gateway_vault_fixture', arguments: { input: 'fixture' } }, 'vault');
    assert.deepEqual(vault.output, { result: 'vault-ok' });

    const failed = await register('gateway_circuit_fixture', 'HTTP', 'gateway-failure', 'input', 'result');
    await api('PUT', failed + '/policies/RETRY', 200, admin,
        { expectedVersion: 0, rule: { maxAttempts: 1, failureThreshold: 1, openSeconds: 30 } });
    const failing = { toolName: 'gateway_circuit_fixture', arguments: { input: 'fixture' } };
    await api('POST', executions, 202, owner, failing, 'circuit-api');
    const apiBase = base;
    base = workerBase;
    const blocked = await api('POST', executions, 502, owner, failing, 'circuit-worker');
    base = apiBase;
    assert.equal(blocked.errorCode, 'CIRCUIT_OPEN');
    assert.equal(gatewayFailureCalls, 1);

    await register('gateway_callback_fixture', 'ASYNC', 'sales', 'jobId', 'result');
    const submitted = await api('POST', executions, 202, owner,
        { toolName: 'gateway_callback_fixture', arguments: { jobId: 'callback-report' } }, 'callback');
    const execution = executions + '/' + submitted.executionId;
    const untilStatus = async expected => {
        for (let attempt = 0; attempt < 50; attempt++) {
            const view = await api('GET', execution, 200, owner);
            if (view.status === expected) return view;
            await sleep(200);
        }
        assert.fail('Callback tool did not reach ' + expected);
    };
    await untilStatus('WAITING_EXTERNAL');
    const deliver = async (body, valid) => {
        const raw = JSON.stringify(body);
        const timestamp = String(Math.floor(Date.now() / 1000));
        const signature = valid ? createHmac('sha256', callbackSigningKey).update('fixture-sales\n' + timestamp + '\n' + raw).digest('hex')
            : '0'.repeat(64);
        return (await fetch(base + '/v1/tool-callbacks/fixture-sales', { method: 'POST', body: raw,
            signal: AbortSignal.timeout(5000), headers: { 'Content-Type': 'application/json', 'X-Callback-Timestamp': timestamp,
                'X-Callback-Signature': 'sha256=' + signature } })).status;
    };
    const callback = { executionId: submitted.executionId, jobId: 'callback-report', status: 'SUCCEEDED', result: { result: 'done' } };
    assert.equal(await deliver(callback, false), 403);
    assert.equal(await deliver(callback, true), 204);
    assert.equal(await deliver(callback, true), 204);
    assert.equal(await deliver({ ...callback, result: { result: 'changed' } }, true), 409);
    assert.deepEqual((await untilStatus('SUCCEEDED')).output, { result: 'done' });
    console.log('Gateway passed Vault KV v2, shared Redis circuit and signed idempotent async callback');
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

async function reconcileSuccessfulTool() {
    const name = 'reconciliation_sales';
    const tool = '/v1/admin/tools/' + name;
    await api('POST', '/v1/admin/tools', 201, admin, { name, description: 'Manual reconciliation fixture', riskLevel: 'MEDIUM' });
    await api('POST', tool + '/versions', 201, admin, {
        version: '1', protocol: 'ASYNC', targetRef: 'sales', timeoutMs: 3000,
        inputSchema: { type: 'object', required: ['jobId'], properties: { jobId: { type: 'string' } } },
        outputSchema: { type: 'object', required: ['rows'], properties: { rows: { type: 'array' }, privateNote: { type: 'string' } } }
    });
    await api('POST', tool + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
    for (const action of ['APPROVE', 'EXECUTE']) {
        await api('PUT', tool + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action, effect: 'ALLOW' });
    }
    await api('PUT', tool + '/permissions', 204, admin,
        { subjectType: 'USER', subjectId: 'operator', action: 'MANAGE', effect: 'ALLOW' });
    await api('PUT', tool + '/policies/APPROVAL', 200, admin, { expectedVersion: 0, rule: { ttlSeconds: 180 } });
    await api('PUT', tool + '/policies/ABAC', 200, admin, { expectedVersion: 0, rule: { values: { '/jobId': ['unknown-report'] } } });
    await api('PUT', tool + '/policies/REDACTION', 200, admin, { expectedVersion: 0, rule: { remove: ['/privateNote'] } });

    const previousCalls = submits;
    const operator = bearer('ops.read ops.write', 'operator');
    const initial = await api('POST', '/v1/runs', 202, owner, { mode: 'WORKFLOW', goal: '核实销售报表',
        dimensions: ['region'], source: { name, version: '1', arguments: { jobId: 'unknown-report' } } }, 'confirmed-external-result');
    const approvalEvents = await sse(initial.runId, 0, event => !!event.data.approvalId);
    await api('POST', '/v1/tools/approvals/' + approvalEvents.at(-1).data.approvalId + '/decisions', 200,
        reviewer, { decision: 'APPROVE' });
    await until(initial.runId, 'NEEDS_RECONCILIATION');
    const steps = '/v1/admin/operations/reconciliation/' + initial.runId + '/steps';
    await api('GET', steps, 404, bearer('ops.read', 'operator', 'isolated'));
    const jobs = await api('GET', steps, 200, operator);
    assert.equal(jobs.length, 1);
    const [job] = jobs;
    assert.equal(job.executionStatus, 'UNKNOWN');
    assert.equal(job.tool, name);
    const path = steps + '/' + job.stepId + '/confirm-success';
    const request = { executionId: job.executionId, result: { rows: fixtureRows, privateNote: '应脱敏' },
        evidenceRef: 'fixture-ledger:001', reason: '已核对外部流水与最终结果' };
    await api('POST', path, 403, bearer('ops.read', 'operator'), request);
    await api('POST', path, 403, bearer('ops.write', 'other-operator'), request);
    await api('POST', path, 404, bearer('ops.write', 'operator', 'isolated'), request);
    await api('POST', path, 409, operator, { ...request, executionId: randomUUID() });
    await api('POST', path, 400, operator, { ...request, result: { invalid: true } });
    assert.equal((await api('GET', steps, 200, operator))[0].executionStatus, 'UNKNOWN');
    await Promise.all([api('POST', path, 204, operator, request), api('POST', path, 204, operator, request)]);
    await api('POST', path, 409, operator, { ...request, evidenceRef: 'fixture-ledger:002' });
    await until(initial.runId, 'SUCCEEDED');
    const report = await api('GET', endpoint(initial.runId) + '/result', 200);
    assert.equal(report.totals.current, 190.75);
    const execution = await api('GET', '/v1/tools/executions/' + job.executionId, 200, owner);
    assert.equal(execution.status, 'SUCCEEDED');
    assert.deepEqual(execution.output.rows, fixtureRows);
    assert.equal(execution.output.privateNote, undefined);
    const events = (await sse(initial.runId, 0)).filter(event => event.type === 'run.reconciled');
    assert.equal(events.length, 1);
    assert.equal(events[0].data.executionId, job.executionId);
    assert.equal(events[0].data.disposition, 'CONFIRMED_SUCCESS');
    assert.deepEqual(await api('GET', steps, 200, operator), []);
    assert.equal(submits - previousCalls, 1);
    console.log('Reconciliation passed MANAGE, tenant scope, ABAC, schema, redaction, concurrent replay and no resubmission');
}

async function monitorFlow() {
    await registerConversationTool('monitor_metric', 'monitor-metric', 'metric', 'value');
    for (const [name, targetRef] of [
        ['monitor_notify', 'monitor-notify'], ['monitor_action', 'monitor-remediate'], ['monitor_unapproved', 'monitor-remediate']
    ]) {
        const tool = '/v1/admin/tools/' + name;
        await api('POST', '/v1/admin/tools', 201, admin, { name, description: name, riskLevel: 'LOW' });
        await api('POST', tool + '/versions', 201, admin, {
            version: '1', protocol: 'HTTP', targetRef, timeoutMs: 3000,
            inputSchema: { type: 'object', additionalProperties: false, required: ['monitorId', 'tickSeq', 'signal'],
                properties: { monitorId: { type: 'string' }, tickSeq: { type: 'integer' }, signal: { type: 'string' } } },
            outputSchema: { type: 'object', required: ['accepted'], properties: { accepted: { type: 'boolean' } } }
        });
        await api('POST', tool + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
        await api('PUT', tool + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'ALLOW' });
    }
    await api('PUT', '/v1/admin/tools/monitor_action/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'APPROVE', effect: 'ALLOW' });
    await api('PUT', '/v1/admin/tools/monitor_action/policies/APPROVAL', 200, admin,
        { expectedVersion: 0, rule: { ttlSeconds: 180 } });
    const monitorActor = bearer('monitors.read monitors.write tools.invoke');
    const foreign = bearer('monitors.read', 'another-monitor-owner');
    const path = '/v1/monitors/' + randomUUID();
    const spec = { tool: 'monitor_metric', version: '1', arguments: { metric: 'sequence' }, field: 'value',
        comparison: 'ABOVE', threshold: 10, everySeconds: 5, lifetimeSeconds: 60, action: 'RECORD' };
    const deliveredSpec = { ...spec, notifyWith: { tool: 'monitor_notify', version: '1' },
        remediateWith: { tool: 'monitor_action', version: '1' } };
    async function settled(url, count) {
        for (let i = 0; i < 100; i++) {
            const ticks = await api('GET', url + '/ticks', 200, monitorActor);
            if (ticks.filter(tick => tick.finishedAt).length >= count) return ticks.filter(tick => tick.finishedAt);
            await sleep(250);
        }
        assert.fail('monitor did not settle expected ticks');
    }
    async function delivery(url, kind, status, signal = 'ALERT') {
        for (let attempt = 0; attempt < 80; attempt++) {
            const rows = await api('GET', url + '/deliveries', 200, monitorActor);
            const found = rows.find(row => row.kind === kind && row.signalEvent === signal && row.status === status);
            if (found) return found;
            await sleep(250);
        }
        assert.fail('monitor delivery did not reach ' + kind + '/' + status);
    }
    await api('PUT', path, 403, intentOnly, spec);
    await api('PUT', path, 403, monitorActor, { ...spec, tool: 'orders_lookup' });
    await api('PUT', path, 403, monitorActor, { ...spec, notifyWith: deliveredSpec.remediateWith });
    const first = await api('PUT', path, 200, monitorActor, deliveredSpec);
    assert.equal((await api('PUT', path, 200, monitorActor, deliveredSpec)).id, first.id);
    await api('PUT', path, 409, monitorActor, { ...deliveredSpec, threshold: 11 });
    await api('GET', path, 404, foreign);
    await api('GET', path + '/deliveries', 404, foreign);
    const pending = await delivery(path, 'REMEDIATE', 'WAITING_APPROVAL');
    assert.equal(monitorRemediations.length, 0);
    const execution = await api('GET', '/v1/tools/executions/' + pending.executionId, 200, monitorActor);
    assert.ok(execution.approvalId);
    await api('POST', '/v1/tools/approvals/' + execution.approvalId + '/decisions',
        200, reviewer, { decision: 'APPROVE' });
    await delivery(path, 'REMEDIATE', 'SUCCEEDED');
    await delivery(path, 'NOTIFY', 'SUCCEEDED');
    const signals = await settled(path, 3);
    assert.deepEqual(signals.map(tick => tick.state), ['NORMAL', 'BREACH', 'BREACH']);
    assert.deepEqual(signals.map(tick => tick.signalEvent), ['RECOVERED', null, 'ALERT']);
    assert.equal(new Set(signals.map(tick => tick.executionId)).size, 3);
    await delivery(path, 'NOTIFY', 'SUCCEEDED', 'RECOVERED');
    assert.deepEqual(monitorNotices.filter(item => item.monitorId === first.id).map(item => item.signal).sort(), ['ALERT', 'RECOVERED']);
    assert.deepEqual(monitorRemediations, [{ monitorId: first.id, tickSeq: 1, signal: 'ALERT' }]);
    await api('POST', path + '/pause', 200, monitorActor);
    const blocked = '/v1/monitors/' + randomUUID();
    await api('PUT', blocked, 200, monitorActor, { ...spec, arguments: { metric: 'blocked' }, action: 'PAUSE',
        remediateWith: { tool: 'monitor_unapproved', version: '1' } });
    const denied = await delivery(blocked, 'REMEDIATE', 'FAILED');
    assert.equal(denied.reason, 'TOOL_POLICY_REQUIRED');
    assert.equal(denied.executionId, null);
    assert.equal(monitorRemediations.length, 1);
    const auto = '/v1/monitors/' + randomUUID();
    await api('PUT', auto, 200, monitorActor, { ...spec, arguments: { metric: 'pause' }, action: 'PAUSE' });
    await settled(auto, 1);
    assert.equal((await api('GET', auto, 200, monitorActor)).reason, 'THRESHOLD_BREACHED');
    await sleep(5200);
    assert.equal(monitorReads.get('pause'), 1);
    await api('POST', auto + '/resume', 200, monitorActor);
    const recovered = await settled(auto, 2);
    assert.equal(recovered[0].signalEvent, 'RECOVERED');
    assert.ok((await api('GET', '/v1/monitors', 200, monitorActor)).some(item => item.id === first.id));
    await api('POST', auto + '/pause', 200, monitorActor);
    console.log('Monitor passed alert/recovery delivery, approval-gated remediation, rollback without approval, isolation and pause/resume');
}

async function monitorSopFlow() {
    const basicFields = { monitorId: { type: 'string' }, tickSeq: { type: 'integer' }, signal: { type: 'string' } };
    for (const phase of ['gate', 'evidence', 'review']) {
        const name = 'monitor_sop_' + phase;
        const tool = '/v1/admin/tools/' + name;
        const properties = phase === 'gate' ? { ...basicFields, skillKey: { type: 'string' },
            skillVersion: { type: 'integer' }, skillVersionId: { type: 'string' } } : basicFields;
        await api('POST', '/v1/admin/tools', 201, admin, { name, description: name, riskLevel: 'LOW' });
        await api('POST', tool + '/versions', 201, admin, {
            version: '1', protocol: 'HTTP', targetRef: 'monitor-sop-' + phase, timeoutMs: 3000,
            inputSchema: { type: 'object', additionalProperties: false, required: Object.keys(properties), properties },
            outputSchema: { type: 'object', required: ['review'], properties: { review: { type: 'string' } } }
        });
        await api('POST', tool + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
        await api('PUT', tool + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'ALLOW' });
    }
    const gatePath = '/v1/admin/tools/monitor_sop_gate';
    await api('PUT', gatePath + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'APPROVE', effect: 'ALLOW' });
    await api('PUT', gatePath + '/policies/APPROVAL', 200, admin, { expectedVersion: 0, rule: { ttlSeconds: 180 } });
    const source = await readFile(new URL('../skills/monitor-alert-review/SKILL.md', import.meta.url), 'utf8');
    const publish = version => api('POST', '/v1/admin/workflow-skills/monitor.alert-review/versions/' + version + '/publish',
        200, skillPublisher, { expectedRevision: version - 1, markdown: source.replace('"version": 1', '"version": ' + version) });
    const first = await publish(1);
    const actor = bearer('monitors.read monitors.write tools.invoke runs.submit runs.read');
    const spec = { tool: 'monitor_metric', version: '1', arguments: { metric: 'sop' }, field: 'value',
        comparison: 'ABOVE', threshold: 10, everySeconds: 5, lifetimeSeconds: 60, action: 'PAUSE',
        remediateWith: { tool: 'monitor_sop_gate', version: '1' },
        remediationSop: { skillKey: 'monitor.alert-review', version: 1 } };
    const path = '/v1/monitors/' + randomUUID();
    await api('PUT', path, 403, bearer('monitors.write tools.invoke'), spec);
    await api('PUT', path, 403, actor, { ...spec, remediationSop: { ...spec.remediationSop, version: 2 } });
    assert.equal((await api('PUT', path, 200, actor, spec)).workflowSkillVersionId, first.id);
    async function untilWorkflow(url, check) {
        for (let attempt = 0; attempt < 100; attempt++) {
            const rows = await api('GET', url + '/deliveries', 200, actor);
            const row = rows.find(item => item.workflow && check(item.workflow));
            if (row) return row;
            await sleep(250);
        }
        assert.fail('monitor SOP did not reach expected state');
    }
    const waiting = await untilWorkflow(path, item => item.status === 'WAITING_APPROVAL');
    assert.equal(waiting.workflow.runId, null);
    assert.equal(monitorSopCalls.length, 0);
    await publish(2);
    const gate = await api('GET', '/v1/tools/executions/' + waiting.executionId, 200, actor);
    await api('POST', '/v1/tools/approvals/' + gate.approvalId + '/decisions', 200, reviewer, { decision: 'APPROVE' });
    const done = await untilWorkflow(path, item => item.status === 'SUCCEEDED');
    assert.equal(done.workflow.skillVersionId, first.id);
    assert.deepEqual(await api('GET', done.workflow.resultUrl, 200, actor), { review: 'review' });
    assert.deepEqual(monitorSopCalls.map(item => item.phase), ['gate', 'evidence', 'review']);
    assert.equal(monitorSopCalls[0].skillVersionId, first.id);
    assert.equal(monitorSopCalls[2].tickSeq, done.seq);
    assert.equal((await api('GET', path + '/deliveries', 200, actor)).length, 1);

    const denied = '/v1/monitors/' + randomUUID();
    await api('PUT', denied, 200, actor, { ...spec, arguments: { metric: 'sop-rejected' } });
    const second = await untilWorkflow(denied, item => item.status === 'WAITING_APPROVAL');
    const rejectedGate = await api('GET', '/v1/tools/executions/' + second.executionId, 200, actor);
    await api('POST', '/v1/tools/approvals/' + rejectedGate.approvalId + '/decisions',
        200, reviewer, { decision: 'REJECT', reason: '测试拒绝处置' });
    const rejected = await untilWorkflow(denied, item => item.status === 'FAILED');
    assert.equal(rejected.workflow.runId, null);
    assert.equal(rejected.workflow.reason, 'GATE_REJECTED');
    assert.equal(monitorSopCalls.length, 3);

    const paused = '/v1/monitors/' + randomUUID();
    await api('PUT', paused, 200, actor, { ...spec, arguments: { metric: 'sop-paused' } });
    const third = await untilWorkflow(paused, item => item.status === 'WAITING_APPROVAL');
    assert.equal((await api('POST', paused + '/pause', 200, actor)).reason, 'MANUAL_PAUSE');
    const pausedGate = await api('GET', '/v1/tools/executions/' + third.executionId, 200, actor);
    await api('POST', '/v1/tools/approvals/' + pausedGate.approvalId + '/decisions', 200, reviewer, { decision: 'APPROVE' });
    const skipped = await untilWorkflow(paused, item => item.status === 'SKIPPED');
    assert.equal(skipped.workflow.runId, null);
    assert.equal(skipped.workflow.reason, 'MONITOR_INACTIVE');
    assert.deepEqual(monitorSopCalls.map(item => item.phase), ['gate', 'evidence', 'review', 'gate']);
    console.log('Monitor SOP passed gate approval, pinned version, ordered DAG, result link, rejection and manual pause');
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
    const catalog = '/v1/admin/workflow-skills';
    const consoleScript = await fetch(base + '/tool-admin/workflows.js', { signal: AbortSignal.timeout(5_000) });
    assert.equal(consoleScript.status, 200);
    assert.match(await consoleScript.text(), /admin-reconcile/);
    const lookup = '/v1/admin/workflow-skills/resolve?command=' + encodeURIComponent('/github-retro');
    const release = (version, markdown, expectedRevision, status, token = skillPublisher) =>
        api('POST', path + '/versions/' + version + '/publish', status, token, { markdown, expectedRevision });
    await release(1, source, 0, 403, admin);
    await api('GET', catalog, 403, admin);
    await api('GET', catalog + '?limit=0', 400, skillPublisher);
    assert.deepEqual((await api('GET', catalog, 200, skillPublisher)).items, []);
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
    const listed = await api('GET', catalog + '?limit=1', 200, skillPublisher);
    assert.deepEqual(listed.items, [{ skillKey: 'github.project-retrospective', version: 2, revision: 2, status: 'PUBLISHED' }]);
    assert.equal(listed.nextCursor, null);
    assert.deepEqual((await api('GET', catalog + '?after=github.project-retrospective', 200, skillPublisher)).items, []);
    assert.deepEqual((await api('GET', catalog, 200, bearer('skills.admin', 'elsewhere', 'other-tenant'))).items, []);
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
    return { source, path, lookup, current: second };
}

async function sopEventFlow({ source, path, lookup, current }) {
    const operator = bearer('ops.read ops.write', 'operator');
    const outbox = '/v1/admin/operations/outbox';
    const delivered = async filter => {
        for (let attempt = 0; attempt < 40; attempt++) {
            const events = await api('GET', outbox + '?dead=false&limit=50', 200, operator);
            const event = events.find(item => item.eventType === 'WorkflowSkillPublished' && filter(item));
            if (event) return event;
            await sleep(250);
        }
        assert.fail('SOP event did not reach the published outbox');
    };
    const original = await delivered(() => true);
    const other = await delivered(item => item.eventId !== original.eventId);
    const third = await api('POST', path + '/versions/3/publish', 200, skillPublisher, {
        expectedRevision: current.revision, markdown: source.replace('"version": 1', '"version": 3')
    });
    await api('POST', outbox + '/' + original.eventId + '/replay', 202, operator, { reason: '验收旧版本事件重投' });
    await delivered(item => item.eventId === original.eventId);
    await delivered(item => ![original.eventId, other.eventId].includes(item.eventId));
    assert.equal((await api('GET', lookup, 200, skillPublisher)).id, third.id);
    assert.equal((await api('GET', path + '?version=2', 200, skillPublisher)).status, 'RETIRED');
    console.log('SOP event delivery and replay preserved the latest trigger and historical version');
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

async function weatherFallbackFlow() {
    await registerConversationTool('weather_forecast_fixture', 'weather-forecast', 'city', 'forecast');
    const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
    const fallbackTurns = '/v1/conversations/' + conversationId + '/turns';
    const fallback = await api('POST', fallbackTurns, 200, conversationOwner, { text: '北京天气怎么样' }, 'weather-fallback');
    assert.equal(fallback.outcomes[0].intent, 'WEATHER.QUERY');
    assert.equal(fallback.outcomes[0].reason, 'SKILL_UNAVAILABLE_DIRECT_FALLBACK');
    assert.equal(fallback.executions[0]?.mode, 'DIRECT');
    let checked;
    for (let i = 0; i < 40; i++) {
        checked = await api('GET', fallbackTurns + '/' + fallback.turnId, 200, conversationOwner);
        if (checked.executions[0].status === 'SUCCEEDED') break;
        await sleep(250);
    }
    assert.equal(checked.executions[0].output?.forecast, '北京天气晴');
    const artifacts = await api('GET', fallbackTurns + '/' + fallback.turnId + '/artifacts', 200, conversationOwner);
    assert.ok(JSON.stringify(artifacts).includes('SKILL_UNAVAILABLE_DIRECT_FALLBACK'));
    const unprivileged = bearer('intents.submit intents.read', 'weather-unprivileged');
    const deniedConversation = await api('POST', '/v1/conversations', 201, unprivileged);
    const blocked = await api('POST', '/v1/conversations/' + deniedConversation.conversationId + '/turns',
        200, unprivileged, { text: '北京天气怎么样' }, 'weather-fallback-no-permission');
    assert.equal(blocked.executions[0]?.reason, 'MISSING_EXECUTION_SCOPE');
    console.log('Weather query passed missing-SOP direct fallback with ToolGateway and traced result');
}

async function skillInputFlow() {
    const producer = await registerConversationTool('weather_city_fixture', 'weather-city', 'city', 'city', {
        city: { type: 'string' }
    });
    await api('PUT', producer + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'DISCOVER', effect: 'ALLOW' });
    await registerConversationTool('weather_forecast_fixture', 'weather-forecast', 'city', 'forecast');
    const spec = { skillKey: 'weather.forecast', version: 1, intentDefinition: 'WEATHER.QUERY@1',
        executionMode: 'WORKFLOW', admissionPolicy: 'workflow.read', triggers: [{ kind: 'COMMAND', key: '/forecast' }],
        inputSchema: { type: 'object', properties: { city: { type: 'string', enum: ['北京', '上海'] } },
            required: ['city'], additionalProperties: false },
        nodes: [{ id: 'fetch', tool: 'weather_forecast_fixture@1', dependsOn: [],
            inputs: { city: 'input.city' }, output: 'GenericResult' }], output: 'steps.fetch.output' };
    const markdown = '# 天气预报\n\n```json\n' + JSON.stringify(spec) + '\n```\n';
    const skill = await api('POST', '/v1/admin/workflow-skills/weather.forecast/versions/1/publish', 200, skillPublisher,
        { expectedRevision: 0, markdown });
    async function submit(variant, text = '查询北京天气，使用预报核验') {
        skillInputVariant = variant;
        const { conversationId } = await api('POST', '/v1/conversations', 201, conversationOwner);
        const turns = '/v1/conversations/' + conversationId + '/turns';
        const turn = await api('POST', turns, 200, conversationOwner, { text }, 'weather-' + variant);
        assert.equal(turn.outcomes[0].ir.arguments.city, '北京', JSON.stringify(turn));
        assert.equal(turn.outcomes[0].ir.workflowSkill.id, skill.id);
        assert.ok(turn.executions[0]?.targetId, JSON.stringify(turn));
        return { run: turn.executions[0].targetId, path: turns + '/' + turn.turnId + '/artifacts',
            mode: turn.executions[0].mode };
    }
    async function plan(path) {
        const details = await api('GET', path, 200, conversationOwner);
        return details.candidates[0].stages.find(stage => stage.artifact === 'PlanVersion').data.nodes;
    }
    const fixed = await submit('fixed', '查询北京天气');
    assert.equal(fixed.mode, 'WORKFLOW');
    assert.deepEqual(await api('GET', (await until(fixed.run, 'SUCCEEDED')).resultUrl, 200), { forecast: '北京天气晴' });
    const bound = await submit('valid');
    assert.equal(bound.mode, 'PLAN_AND_EXECUTE');
    assert.deepEqual(await api('GET', (await until(bound.run, 'SUCCEEDED')).resultUrl, 200), { forecast: '上海天气晴' });
    const graph = await plan(bound.path);
    const binding = graph.find(node => node.skillNode === '$input');
    assert.deepEqual(binding.dependsOn, ['normalize']);
    assert.deepEqual(binding.inputBindings, { city: 'steps.normalize.output.city' });
    assert.ok(graph.find(node => node.key === 'forecast').dependsOn.includes(binding.key));
    assert.equal(binding.skillVersion.id, skill.id);
    const invalid = await submit('bad-value');
    await until(invalid.run, 'FAILED');
    assert.equal((await plan(invalid.path)).find(node => node.skillNode === '$input').reason, 'SKILL_INPUT_SCHEMA_INVALID');
    for (const [variant, reason] of [['undeclared', 'PLANNER_ORPHAN_NODE'], ['disallowed', 'PLANNER_SKILL_INPUT_DENIED']]) {
        const rejected = await submit(variant);
        assert.equal((await until(rejected.run, 'FAILED')).reason, reason);
    }
    assert.deepEqual(boundForecastCalls, ['北京', '上海']);
    console.log('Skill input passed fixed and upstream workflows, contract enforcement and published binding trace');
}

async function multiSopFlow() {
    const schema = fields => ({ type: 'object', required: Object.keys(fields), additionalProperties: false,
        properties: Object.fromEntries(Object.entries(fields).map(([name, type]) => [name, { type }])) });
    for (const [name, endpoint, risk, input, output] of [
        ['composition_evidence', 'composition-evidence', 'LOW', { period: 'string' }, { evidence: 'string' }],
        ['composition_ticket', 'composition-ticket', 'HIGH', { evidence: 'string' }, { ticketId: 'string', accepted: 'boolean' }]
    ]) {
        const path = '/v1/admin/tools/' + name;
        await api('POST', '/v1/admin/tools', 201, admin, { name, description: name, riskLevel: risk });
        await api('POST', path + '/versions', 201, admin, { version: '1', protocol: 'HTTP', targetRef: endpoint,
            inputSchema: schema(input), outputSchema: schema(output), timeoutMs: 3000 });
        await api('POST', path + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
        await api('PUT', path + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'EXECUTE', effect: 'ALLOW' });
    }
    function publish(key, version, field, expectedStatus = 200) {
        const write = key === 'finance.ticket';
        const definition = { skillKey: key, version, intentDefinition: 'FIXTURE.' + (write ? 'TICKET' : 'EVIDENCE') + '@1',
            executionMode: 'WORKFLOW', admissionPolicy: write ? 'workflow.approved-write' : 'workflow.read',
            triggers: write ? [] : [{ kind: 'COMMAND', key: '/evidence-fixture' }], inputSchema: schema({ [field]: 'string' }),
            nodes: [{ id: 'execute', tool: 'composition_' + (write ? 'ticket' : 'evidence') + '@1', dependsOn: [],
                inputs: { [field]: 'input.' + field }, output: 'GenericResult' }], output: 'steps.execute.output' };
        return api('POST', '/v1/admin/workflow-skills/' + key + '/versions/' + version + '/publish', expectedStatus, skillPublisher,
            { expectedRevision: version - 1, markdown: '# 组合测试\n\n```json\n' + JSON.stringify(definition) + '\n```\n' });
    }
    const evidence = await publish('finance.evidence', 1, 'period');
    await publish('finance.ticket', 1, 'evidence', 409);
    const path = '/v1/admin/tools/composition_ticket';
    await api('PUT', path + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'runtime-tester', action: 'APPROVE', effect: 'ALLOW' });
    await api('PUT', path + '/policies/APPROVAL', 200, admin, { expectedVersion: 0, rule: { ttlSeconds: 180 } });
    const ticket = await publish('finance.ticket', 1, 'evidence');
    const actor = bearer('intents.submit intents.read runs.submit runs.read tools.invoke skills.execute-write');
    async function submit(variant, token = actor) {
        multiSopVariant = variant;
        const { conversationId } = await api('POST', '/v1/conversations', 201, token);
        const turns = '/v1/conversations/' + conversationId + '/turns';
        const turn = await api('POST', turns, 200, token, { text: '分析上季度财务报表' }, 'multiple-sop-' + variant);
        return { run: turn.executions[0]?.targetId, execution: turn.executions[0], artifacts: turns + '/' + turn.turnId + '/artifacts' };
    }
    async function approval(run) {
        await until(run, 'WAITING_APPROVAL');
        const stream = await sse(run, 0, event => !!event.data.approvalId);
        assert.equal(stream.at(-1).data.node, 'publish');
        return '/v1/tools/approvals/' + stream.at(-1).data.approvalId + '/decisions';
    }

    assert.equal((await submit('missing-scope', conversationOwner)).execution.reason, 'MISSING_WRITE_SOP_SCOPE');
    assert.equal((await until((await submit('omit')).run, 'FAILED')).reason, 'PLANNER_SKILL_REQUIRED');
    assert.equal((await until((await submit('invalid-binding')).run, 'FAILED')).reason, 'PLANNER_OUTPUT_FIELD');
    assert.deepEqual(multiSopCalls, []);
    const valid = await submit('valid');
    const decision = await approval(valid.run);
    assert.deepEqual(multiSopCalls, [{ node: 'read', args: { period: '上季度' } }]);
    const newer = await publish('finance.ticket', 2, 'evidence');
    assert.notEqual(ticket.id, newer.id);
    await api('POST', decision, 403, bearer('tools.approve'), { decision: 'APPROVE' });
    await api('POST', decision, 200, reviewer, { decision: 'APPROVE' });
    const view = await until(valid.run, 'SUCCEEDED');
    assert.deepEqual(await api('GET', view.resultUrl, 200, actor), { ticketId: 'T-TEST', accepted: true });
    assert.deepEqual(multiSopCalls[1], { node: 'write', args: { evidence: '上季度证据' } });
    const trace = await api('GET', valid.artifacts, 200, actor);
    const nodes = trace.candidates[0].stages.find(stage => stage.artifact === 'PlanVersion').data.nodes;
    assert.equal(nodes.find(node => node.key === 'read').skillVersion.id, evidence.id);
    assert.equal(nodes.find(node => node.key === 'publish').skillVersion.id, ticket.id);
    assert.deepEqual(nodes.find(node => node.skillVersion?.id === ticket.id && node.skillNode === '$input').inputBindings,
        { evidence: 'steps.read.output.evidence' });

    const failed = await submit('failed-gate');
    await api('POST', await approval(failed.run), 200, reviewer, { decision: 'APPROVE' });
    const terminal = await until(failed.run, 'FAILED');
    assert.equal(terminal.iteration, 1);
    assert.deepEqual(multiSopCalls.map(item => item.node), ['read', 'write', 'read', 'write']);
    console.log('Multiple SOPs passed pinned read and approved write chaining, isolated binding, authorization and terminal gate');
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
    const reader = bearer('intents.submit intents.read refunds.submit', 'mixed-refund-reader');
    const mixed = await api('POST', '/v1/conversations', 201, reader);
    const mixedTurns = '/v1/conversations/' + mixed.conversationId + '/turns';
    const draft = await api('POST', mixedTurns, 200, reader, { text: '帮我办理订单退款' }, 'mixed-refund-draft');
    const split = await api('POST', mixedTurns, 200, reader,
        { text: '订单号是 SO001，商品有质量问题。另外帮我查询一下客户 C002。' }, 'mixed-refund-and-customer');
    assert.deepEqual(split.outcomes.map(outcome => [outcome.intent, outcome.relation]), [
        ['ORDER.REFUND', 'CONTINUATION'], ['CUSTOMER.QUERY', 'NEW_TASK']
    ]);
    assert.equal(split.outcomes[0].taskId, draft.outcomes[0].taskId);
    assert.equal(split.outcomes[0].ir.arguments.reason, '商品有质量问题');
    assert.equal(split.outcomes[1].ir.arguments.customerId, 'C002');

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
    const current = await api('POST', turns, 200, requester, { text: '查询订单' }, 'refund-context-active-run');
    const trace = await api('GET', turns + '/' + current.turnId + '/artifacts', 200, requester);
    const snapshot = trace.candidates[0].stages.find(stage => stage.artifact === 'ContextSnapshot').data;
    assert.ok(snapshot.contextCounts.activeRunCount >= 1);
    const limitedTrace = await api('GET', turns + '/' + current.turnId + '/artifacts', 200, bearer('intents.read'));
    const limitedSnapshot = limitedTrace.candidates[0].stages.find(stage => stage.artifact === 'ContextSnapshot').data;
    assert.equal(limitedSnapshot.contextCounts.activeRunCount, undefined);
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

async function runRuntimeSuite() {
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
    await skillInputFlow();
    await api('PUT', '/v1/admin/tools/orders_lookup/permissions', 204, admin,
        {subjectType:'ROLE', subjectId:'runtime-tester', action:'EXECUTE', effect:'ALLOW'});
    await generalRouting();
    await memoryLoopFlow();
    await conversationSummaryFlow();
    await operationsFlow(uncertain.runId);
    await reconcileSuccessfulTool();
    await historyFlow();
    await predefinedRuleFlow();
    await monitorFlow();
    await monitorSopFlow();
}

try {
    if (process.argv.includes('--gateway-production-only')) {
        base = await start(false).ready;
        await gatewayProductionFlow(await start(false).ready);
    } else if (process.argv.includes('--reconciliation-only')) {
        base = await start(false).ready;
        await start(true).ready;
        await reconcileSuccessfulTool();
    } else if (process.argv.includes('--sop-events-only')) {
        base = await start(false).ready;
        const versions = await skillPublication();
        await start(true).ready;
        await sopEventFlow(versions);
    } else if (process.argv.includes('--monitor-only')) {
        base = await start(false).ready;
        await start(true).ready;
        await monitorFlow();
        await monitorSopFlow();
    } else if (process.argv.includes('--conversation-summary-only')) {
        base = await start(false).ready;
        await conversationSummaryFlow();
    } else if (process.argv.includes('--multi-sop-only')) {
        base = await start(false).ready;
        await start(true).ready;
        await multiSopFlow();
    } else if (process.argv.includes('--skill-input-only')) {
        base = await start(false).ready;
        await start(true).ready;
        await skillInputFlow();
    } else if (process.argv.includes('--refund-only')) {
        base = await start(false).ready;
        await start(true).ready;
        await refundFixedWorkflow();
    } else if (process.argv.includes('--weather-fallback-only')) {
        base = await start(false).ready;
        await start(true).ready;
        await weatherFallbackFlow();
    } else {
        await runRuntimeSuite();
    }
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
