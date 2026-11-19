import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { createServer } from 'node:http';
import { once } from 'node:events';

// 使用空白测试库。临时 JWT 只由本进程签发，下游响应全部为模拟数据。
const jar = process.argv[2];
const modelKey = process.env.DEEPSEEK_API_KEY;
assert.ok(jar && process.env.ENTERPRISE_DB_URL && process.env.ENTERPRISE_DB_PASSWORD,
    '需要 JAR 路径、测试库 ENTERPRISE_DB_URL 和 ENTERPRISE_DB_PASSWORD');
assert.ok(modelKey, '需要 DEEPSEEK_API_KEY');

const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const kid = 'local-smoke';
let received = 0;
const fixture = createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/jwks') {
        res.end(JSON.stringify({ keys: [{ ...publicKey.export({ format: 'jwk' }), use: 'sig', alg: 'RS256', kid }] }));
        return;
    }
    if (req.url === '/orders' && req.method === 'POST') {
        received++;
        let body = '';
        for await (const part of req) body += part;
        const { orderId } = JSON.parse(body);
        if (orderId === 'SLOW') {
            setTimeout(() => res.end('{"orderId":"SLOW","status":"GW_READY"}'), 850);
        } else if (orderId === 'CONCURRENT') {
            setTimeout(() => res.end('{"orderId":"CONCURRENT","status":"GW_READY"}'), 150);
        } else {
            res.end(JSON.stringify({ orderId, status: 'GW_READY' }));
        }
        return;
    }
    res.writeHead(404).end('{}');
});
fixture.listen(0, '127.0.0.1');
await once(fixture, 'listening');
const issuer = 'http://127.0.0.1:' + fixture.address().port;
const tenant = 'smoke-' + randomUUID();
function token(scope, aud = 'tools', tenantId = tenant) {
    const json = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const part = json({ alg: 'RS256', kid }) + '.' + json({
        iss: issuer, aud, exp: Math.floor(Date.now() / 1000) + 300, sub: 'operator',
        agent_id: 'llm-smoke', tenant_id: tenantId, scope, roles: ['tool-tester']
    });
    return part + '.' + sign('RSA-SHA256', Buffer.from(part), privateKey).toString('base64url');
}

const appEnv = { ...process.env };
for (const name of ['DEEPSEEK_API_KEY', 'DASHSCOPE_API_KEY', 'ZHIPUAI_API_KEY', 'KIMI_API_KEY',
    'EMBEDDING_API_KEY', 'TAVILY_API_KEY']) delete appEnv[name];
appEnv.SPRING_APPLICATION_JSON = JSON.stringify({
    enterprise: { gateway: { jwt: { issuer, audience: 'tools', 'jwk-set-uri': issuer + '/jwks' },
        targets: { orders: issuer + '/orders' } } }
});
const app = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java',
    ['-jar', jar, '--spring.profiles.active=storage,gateway', '--server.address=127.0.0.1',
        '--server.port=0', '--spring.main.banner-mode=off'],
    { env: appEnv, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
let startup = '';
let base;
const closed = once(app, 'close');
function waitForPort() {
    return new Promise((resolve, reject) => {
        const deadline = setTimeout(() => reject(Error('应用在启动预算内未就绪\n' + startup.slice(-3500))), 30_000);
        app.on('error', error => { clearTimeout(deadline); reject(error); });
        app.on('exit', code => {
            clearTimeout(deadline);
            reject(Error('应用退出 ' + code + '\n' + startup.slice(-4500)));
        });
        app.stderr.on('data', chunk => { startup = (startup + chunk).slice(-30_000); });
        app.stdout.on('data', chunk => {
            startup = (startup + chunk).slice(-30_000);
            const port = startup.match(/Tomcat started on port (\d+)/)?.[1];
            if (port && startup.includes('Started EnterpriseAgentApplication')) {
                clearTimeout(deadline);
                resolve('http://127.0.0.1:' + port);
            }
        });
    });
}
async function request(method, path, expected, credential, body, key) {
    const headers = {};
    if (credential) headers.Authorization = 'Bearer ' + credential;
    if (key) headers['Idempotency-Key'] = key;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await fetch(base + path, { method, headers,
        body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(8000) });
    const text = await res.text();
    assert.ok([expected].flat().includes(res.status), method + ' ' + path + ': ' + res.status + ' ' + text);
    return text ? JSON.parse(text) : null;
}
async function chat(messages, tools) {
    const response = await fetch('https://api.deepseek.com/chat/completions', {
        method: 'POST', headers: { Authorization: 'Bearer ' + modelKey, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: 'deepseek-flash', thinking: { type: 'disabled' }, messages, tools,
            tool_choice: 'auto', max_tokens: 256 }),
        signal: AbortSignal.timeout(45_000)
    });
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        const kind = String(body.error?.code ?? body.error?.type ?? 'unknown').replace(/[^a-zA-Z0-9_ -]/g, '');
        throw Error('DeepSeek returned HTTP ' + response.status + ' (' + kind + ')');
    }
    return (await response.json()).choices[0].message;
}

try {
    base = await waitForPort();
    const admin = token('tools.admin');
    const user = token('tools.invoke');
    const registration = { name: 'orders_lookup', description: '查找测试订单的当前状态', riskLevel: 'LOW' };
    const accessPath = '/v1/admin/tools/orders_lookup';
    const search = { query: '订单', limit: 10 };
    await request('POST', '/v1/tools/search', 401, null, search);
    await request('POST', '/v1/tools/search', 401, token('tools.invoke', 'wrong-audience'), search);
    await request('POST', '/v1/admin/tools', 403, user, registration);
    await request('POST', '/v1/admin/tools', 201, admin, registration);
    const inputSchema = { type: 'object', properties: { orderId: { type: 'string', minLength: 1 } },
        required: ['orderId'], additionalProperties: false };
    const outputSchema = { type: 'object', properties: { orderId: { type: 'string' }, status: { type: 'string' } },
        required: ['orderId', 'status'], additionalProperties: false };
    const version = { version: '1', targetRef: 'orders', timeoutMs: 350, inputSchema, outputSchema };
    await request('POST', accessPath + '/versions', 400, admin,
        { ...version, inputSchema: { $ref: 'https://example.invalid/schema' } });
    await request('POST', accessPath + '/versions', 201, admin, version);
    await request('POST', accessPath + '/publish', 400, admin, { version: '1' });
    await request('POST', accessPath + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
    assert.deepEqual(await request('POST', '/v1/tools/search', 200, user, search), []);
    for (const action of ['DISCOVER', 'EXECUTE']) {
        await request('PUT', accessPath + '/permissions', 204, admin,
            { subjectType: 'ROLE', subjectId: 'tool-tester', action, effect: 'ALLOW' });
    }
    assert.deepEqual(await request('POST', '/v1/tools/search', 200, token('tools.invoke', 'tools', 'other-tenant'), search), []);
    const hits = await request('POST', '/v1/tools/search', 200, user, search);
    assert.equal(hits[0].name, registration.name);
    const contract = await request('GET', '/v1/tools/orders_lookup/schema', 200, user);
    assert.deepEqual(contract.inputSchema, inputSchema);

    const invoke = (argumentsValue, key, expected = 200, version) => request('POST', '/v1/tools/executions', expected,
        user, { toolName: registration.name, arguments: argumentsValue, version }, key);
    assert.equal((await invoke({ orderId: 'GWLOCAL' }, null, 400)).code, 'INVALID');
    await invoke({}, 'invalid', 400);
    const deny = { subjectType: 'USER', subjectId: 'operator', action: 'EXECUTE', effect: 'DENY' };
    await request('PUT', accessPath + '/permissions', 204, admin, deny);
    await invoke({ orderId: 'GWTEST123' }, 'denied', 403);
    await request('PUT', accessPath + '/permissions', 204, admin, { ...deny, effect: 'ALLOW' });
    assert.equal(received, 0);
    const first = await invoke({ orderId: 'GWLOCAL' }, 'first');
    const duplicate = await invoke({ orderId: 'GWLOCAL' }, 'first');
    assert.equal(duplicate.executionId, first.executionId);
    assert.equal(duplicate.replayed, true);
    await invoke({ orderId: 'ANOTHER' }, 'first', 409);
    assert.equal((await request('GET', '/v1/tools/executions/' + first.executionId, 200, user)).status, 'SUCCEEDED');
    const slow = await invoke({ orderId: 'SLOW' }, 'slow', 202);
    assert.equal(slow.status, 'UNKNOWN');
    assert.equal((await invoke({ orderId: 'SLOW' }, 'slow', 202)).executionId, slow.executionId);
    assert.equal(received, 2);
    console.log('Gateway passed authentication, tenant scope, deny precedence, Schema, replay, conflict and timeout checks');

    const tools = [{ type: 'function', function: { name: hits[0].name, description: hits[0].description,
        parameters: contract.inputSchema } }];
    const messages = [{ role: 'user', content: '请调用订单查询工具查询虚构测试订单 GWTEST123，然后原样报告工具返回的订单状态。' }];
    const choice = await chat(messages, tools);
    assert.equal(choice.tool_calls?.length, 1, '模型应返回一次 tool call');
    const call = choice.tool_calls[0];
    assert.equal(call.function.name, registration.name);
    const args = JSON.parse(call.function.arguments);
    assert.equal(args.orderId, 'GWTEST123');
    const result = await invoke(args, 'model-' + randomUUID(), 200, contract.version);
    assert.deepEqual(result.output, { orderId: 'GWTEST123', status: 'GW_READY' });
    const answer = await chat([...messages, choice,
        { role: 'tool', tool_call_id: call.id, content: JSON.stringify(result.output) }], tools);
    assert.ok(answer.content?.includes('GW_READY'), '模型应引用工具结果');
    assert.equal(received, 3);
    console.log('deepseek-flash → orders_lookup → authenticated ToolGateway → HTTP fixture → model response: GW_READY');

    const parallel = await Promise.all([
        invoke({ orderId: 'CONCURRENT' }, 'concurrent', [200, 202]),
        invoke({ orderId: 'CONCURRENT' }, 'concurrent', [200, 202])
    ]);
    assert.equal(parallel[0].executionId, parallel[1].executionId);
    assert.equal(parallel.filter(item => item.replayed).length, 1);
    assert.equal(received, 4);
    await request('POST', accessPath + '/versions', 201, admin, { ...version, version: '2' });
    await request('POST', accessPath + '/publish', 409, admin, { version: '2', expectedRevision: 0 });
    await request('POST', accessPath + '/publish', 200, admin, { version: '2', expectedRevision: 1 });
    assert.equal((await invoke({ orderId: 'GWLOCAL' }, 'first')).versionId, first.versionId);
    await invoke({ orderId: 'GWLOCAL' }, 'first', 409, '2');
    await request('POST', accessPath + '/disable', 200, admin, { expectedRevision: 2 });
    await invoke({ orderId: 'GWLOCAL' }, 'disabled', 404);

    const risky = '/v1/admin/tools/orders_high_risk';
    await request('POST', '/v1/admin/tools', 201, admin,
        { ...registration, name: 'orders_high_risk', riskLevel: 'HIGH' });
    await request('POST', risky + '/versions', 201, admin, version);
    await request('POST', risky + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
    await request('PUT', risky + '/permissions', 204, admin,
        { subjectType: 'ROLE', subjectId: 'tool-tester', action: 'EXECUTE', effect: 'ALLOW' });
    const blocked = await request('POST', '/v1/tools/executions', 403, user,
        { toolName: 'orders_high_risk', arguments: { orderId: 'GWLOCAL' } }, 'high-risk');
    assert.equal(blocked.code, 'POLICY_REQUIRED');
    assert.equal(received, 4);
    console.log('Concurrent replay, version pinning, revision checks, disable and high-risk blocking passed');
} finally {
    app.kill();
    fixture.closeAllConnections();
    fixture.close();
    await closed.catch(() => {});
}
