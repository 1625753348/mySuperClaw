import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';

// 使用空白测试库。临时 JWT 只由本进程签发，下游响应全部为模拟数据。
const jar = process.argv[2];
const modelKey = process.env.DEEPSEEK_API_KEY;
assert.ok(jar && process.env.ENTERPRISE_DB_URL && process.env.ENTERPRISE_DB_PASSWORD,
    '需要 JAR 路径、测试库 ENTERPRISE_DB_URL 和 ENTERPRISE_DB_PASSWORD');
assert.ok(modelKey, '需要 DEEPSEEK_API_KEY');

const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const kid = 'local-smoke';
let received = 0;
let mcpCalls = 0;
const webInput = { type: 'object', properties: { query: { type: 'string' } }, required: ['query'] };
const webOutput = { type: 'object', properties: { status: { type: 'string' } }, required: ['status'] };
async function payload(req) {
    let body = '';
    for await (const part of req) body += part;
    return JSON.parse(body);
}
const fixture = createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/jwks') {
        res.end(JSON.stringify({ keys: [{ ...publicKey.export({ format: 'jwk' }), use: 'sig', alg: 'RS256', kid }] }));
        return;
    }
    if (req.url === '/orders' && req.method === 'POST') {
        received++;
        const { orderId } = await payload(req);
        if (orderId === 'SLOW') {
            setTimeout(() => res.end('{"orderId":"SLOW","status":"GW_READY"}'), 850);
        } else if (orderId === 'CONCURRENT') {
            setTimeout(() => res.end('{"orderId":"CONCURRENT","status":"GW_READY"}'), 150);
        } else {
            res.end(JSON.stringify({ orderId, status: 'GW_READY' }));
        }
        return;
    }
    if (req.url === '/embeddings' && req.method === 'POST') {
        const { input } = await payload(req);
        const vector = /环球时讯|实时新闻|网络搜索新闻/.test(input) ? [1, 0, 0, 0]
            : /订单/.test(input) ? [0, 1, 0, 0] : [0, 0, 0, 1];
        res.end(JSON.stringify({ data: [{ embedding: vector }] }));
        return;
    }
    if (req.url === '/mcp') {
        if (req.headers.authorization !== 'Bearer smoke-mcp-token') {
            res.writeHead(401).end('{}');
            return;
        }
        if (req.method !== 'POST') {
            res.writeHead(405).end('{}');
            return;
        }
        const message = await payload(req);
        if (message.id === undefined) {
            res.writeHead(202).end();
            return;
        }
        let result;
        if (message.method === 'initialize') {
            result = { protocolVersion: '2025-11-25', capabilities: { tools: {} },
                serverInfo: { name: 'mock-web', version: '1' } };
        } else if (message.method === 'tools/list') {
            result = { tools: [{ name: 'web_search', description: '网络搜索实时新闻', inputSchema: webInput, outputSchema: webOutput }] };
        } else if (message.method === 'tools/call' && message.params.name === 'web_search') {
            mcpCalls++;
            result = { content: [{ type: 'text', text: 'MCP_READY' }], structuredContent: { status: 'MCP_READY' }, isError: false };
        } else {
            res.end(JSON.stringify({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Unknown method' } }));
            return;
        }
        res.end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }));
        return;
    }
    res.writeHead(404).end('{}');
});
fixture.listen(0, '127.0.0.1');
await once(fixture, 'listening');
const issuer = 'http://127.0.0.1:' + fixture.address().port;
const tenant = 'smoke-' + randomUUID();
const collection = 'gateway_smoke_' + tenant.slice(6).replaceAll('-', '');
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
        targets: { orders: issuer + '/orders' },
        'mcp-servers': { web: { uri: issuer + '/mcp', 'allowed-tenants': [tenant], 'bearer-token': 'smoke-mcp-token' } },
        'mcp-tools': { 'web-search': { server: 'web', 'tool-name': 'web_search' } },
        discovery: { 'semantic-enabled': true, 'embedding-uri': issuer + '/embeddings', 'api-key': 'fixture',
            model: 'fixture', collection, dimension: 4, 'index-interval-ms': 100 }
    } }
});
const fixtureClasspath = process.env.ENTERPRISE_GATEWAY_SMOKE_CLASSPATH;
const app = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java',
    [...(fixtureClasspath ? ['-cp', fixtureClasspath, 'ToolGatewaySmokeApplication'] : ['-jar', jar]),
        '--spring.profiles.active=storage,gateway', '--server.address=127.0.0.1',
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
            if (port && /Started (EnterpriseAgentApplication|ToolGatewaySmokeApplication)/.test(startup)) {
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
    const consolePage = await fetch(base + '/tool-admin/index.html');
    assert.equal(consolePage.status, 200);
    assert.match(await consolePage.text(), /工具目录/);
    assert.match(consolePage.headers.get('content-security-policy'), /script-src 'self'/);
    assert.equal((await fetch(base + '/tool-admin/admin.js')).status, 200);
    assert.equal((await fetch(base + '/tool-admin/admin.css')).status, 200);
    await request('GET', '/v1/admin/tools', 401, null);
    await request('GET', '/v1/admin/tools', 403, user);
    const targetOptions = await request('GET', '/v1/admin/tools/options/targets?protocol=MCP', 200, admin);
    assert.deepEqual(targetOptions.bindings, [{ alias: 'web-search', server: 'web', toolName: 'web_search' }]);
    assert.deepEqual((await request('GET', '/v1/admin/tools/options/targets?protocol=MCP', 200,
        token('tools.admin', 'tools', 'other-tenant'))).servers, []);
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
    const consoleDraft = { tool: { name: 'console_order', description: '管理台订单测试', riskLevel: 'LOW', category: '前台联调' },
        version: { ...version, protocol: 'HTTP' } };
    await request('POST', '/v1/admin/tools/drafts', 400, admin, {
        ...consoleDraft, version: { ...consoleDraft.version, targetRef: 'missing-alias' }
    });
    await request('GET', '/v1/admin/tools/console_order', 404, admin);
    await request('POST', '/v1/admin/tools/drafts', 201, admin, consoleDraft);
    const draftPage = await request('GET', '/v1/admin/tools?protocol=HTTP&status=DRAFT&query=' + encodeURIComponent('前台联调'), 200, admin);
    assert.equal(draftPage.items[0]?.name, 'console_order');
    assert.equal(draftPage.items[0]?.versionCount, 1);
    assert.equal((await request('GET', '/v1/admin/tools?protocol=MCP', 200, admin)).items.length, 0);
    assert.equal((await request('GET', '/v1/admin/tools?protocol=UNBOUND', 200, admin)).items[0]?.name, registration.name);
    const draftDetails = await request('GET', '/v1/admin/tools/console_order', 200, admin);
    assert.equal(draftDetails.versions[0]?.protocol, 'HTTP');
    assert.equal(draftDetails.versions[0]?.status, 'DRAFT');
    assert.deepEqual((await request('GET', '/v1/admin/tools/console_order/versions/1', 200, admin)).inputSchema, inputSchema);
    await request('GET', '/v1/admin/tools/console_order', 404, token('tools.admin', 'tools', 'other-tenant'));
    await request('POST', '/v1/admin/tools/console_order/publish', 200, admin, { version: '1', expectedRevision: 0 });
    const published = (await request('GET', '/v1/admin/tools?protocol=HTTP&status=ACTIVE', 200, admin)).items[0];
    assert.equal(published.currentVersion, '1');
    await request('POST', '/v1/admin/tools/console_order/disable', 200, admin, { expectedRevision: 1 });
    assert.equal((await request('GET', '/v1/admin/tools?status=DISABLED', 200, admin)).items[0]?.name, 'console_order');
    console.log('Admin console assets, tenant catalog, atomic drafts and publication APIs passed');
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

    async function registerTool(name, description, toolVersion, category) {
        const path = '/v1/admin/tools/' + name;
        const tool = await request('POST', '/v1/admin/tools', 201, admin, { name, description, riskLevel: 'LOW', category });
        if (category) assert.equal(tool.category, category);
        await request('POST', path + '/versions', 201, admin, { ...version, ...toolVersion });
        await request('POST', path + '/publish', 200, admin, { version: '1', expectedRevision: 0 });
        for (const action of ['DISCOVER', 'EXECUTE']) {
            await request('PUT', path + '/permissions', 204, admin,
                { subjectType: 'ROLE', subjectId: 'tool-tester', action, effect: 'ALLOW' });
        }
    }

    const catalogPath = '/v1/admin/tools/mcp/web/catalog';
    await request('GET', catalogPath, 404, token('tools.admin', 'tools', 'other-tenant'));
    const catalog = await request('GET', catalogPath, 200, admin);
    assert.equal(catalog.tools[0].name, 'web_search');
    assert.deepEqual(catalog.tools[0].inputSchema, webInput);
    assert.deepEqual(await request('POST', '/v1/tools/search', 200, user, { query: '网络搜索新闻', limit: 5 }), []);
    await registerTool('aa_news', '归档新闻公告', {});
    await registerTool('web_search', catalog.tools[0].description,
        { protocol: 'MCP', targetRef: 'web-search', inputSchema: webInput, outputSchema: webOutput, timeoutMs: 6000 }, '媒体资讯');
    const ranked = await request('POST', '/v1/tools/search', 200, user, { query: '网络搜索新闻', limit: 5 });
    assert.equal(ranked[0].name, 'web_search');
    assert.ok(ranked[0].bm25Score > ranked.find(tool => tool.name === 'aa_news').bm25Score);
    assert.equal(ranked[0].protocol, 'MCP');
    assert.equal(ranked[0].category, '媒体资讯');
    const fromMcp = await request('POST', '/v1/tools/executions', 200, user,
        { toolName: 'web_search', arguments: { query: '测试新闻' } }, 'mcp-once');
    assert.equal(fromMcp.output.status, 'MCP_READY');
    assert.equal(mcpCalls, 1);

    let semantic;
    for (let attempt = 0; attempt < 20; attempt++) {
        semantic = await request('POST', '/v1/tools/search', 200, user, { query: '环球时讯', limit: 5 });
        if (semantic[0]?.name === 'web_search' && semantic[0].semanticScore > 0) break;
        await delay(350);
    }
    assert.equal(semantic[0]?.name, 'web_search', '发布事件应构建 Milvus 工具向量');
    assert.equal(semantic[0].bm25Score, 0);
    assert.equal((await request('POST', '/v1/tools/search', 200,
        token('tools.invoke', 'tools', 'other-tenant'), { query: '环球时讯', limit: 5 })).length, 0);
    await request('PUT', '/v1/admin/tools/web_search/permissions', 204, admin, deny);
    assert.ok(!(await request('POST', '/v1/tools/search', 200, user, { query: '环球时讯', limit: 5 }))
        .some(tool => tool.name === 'web_search'));
    await request('PUT', '/v1/admin/tools/web_search/permissions', 204, admin, { ...deny, effect: 'ALLOW' });
    const indexed = await request('POST', '/v1/admin/tools/reindex?after=orders&limit=1', 200, admin);
    assert.equal(indexed.indexed, 1);
    assert.deepEqual(indexed.failed, []);
    await request('POST', '/v1/admin/tools/web_search/disable', 200, admin, { expectedRevision: 1 });
    const filtered = await request('POST', '/v1/tools/search', 200, user, { query: '环球时讯', limit: 5 });
    assert.ok(!filtered.some(tool => tool.name === 'web_search'), '已停用的向量候选必须立即失效');
    console.log('BM25 ranking, MCP list/call, Milvus semantic-only retrieval and stale candidate filtering passed');

    if (fixtureClasspath) {
        await registerTool('local_lookup', '本地 Java 查询', { protocol: 'LOCAL', targetRef: 'local-lookup', timeoutMs: 2000 });
        await registerTool('grpc_lookup', 'gRPC 查询', { protocol: 'GRPC', targetRef: 'grpc-lookup',
            timeoutMs: 3000, outputSchema: inputSchema });
        const args = { orderId: 'PROTO1' };
        const local = await request('POST', '/v1/tools/executions', 200, user,
            { toolName: 'local_lookup', arguments: args }, 'local');
        assert.equal(local.output.status, 'LOCAL_READY');
        const rpc = await request('POST', '/v1/tools/executions', 200, user,
            { toolName: 'grpc_lookup', arguments: args }, 'grpc');
        assert.deepEqual(rpc.output, args);
        console.log('Registered Java LocalTool and real unary gRPC both passed through Gateway');
    }
} catch (error) {
    console.error(startup.split(/\r?\n/).filter(line => /ERROR|Exception|Caused by/.test(line) && !line.trim().startsWith('at ')).slice(-18).join('\n'));
    throw error;
} finally {
    app.kill();
    fixture.closeAllConnections();
    fixture.close();
    await closed.catch(() => {});
    const milvus = (process.env.ENTERPRISE_MILVUS_URI ?? 'http://127.0.0.1:19530').replace(/\/$/, '');
    const headers = { 'Content-Type': 'application/json' };
    if (process.env.ENTERPRISE_MILVUS_TOKEN) headers.Authorization = 'Bearer ' + process.env.ENTERPRISE_MILVUS_TOKEN;
    await fetch(milvus + '/v2/vectordb/collections/drop', {
        method: 'POST', headers, body: JSON.stringify({ collectionName: collection }), signal: AbortSignal.timeout(3000)
    }).catch(() => console.warn('可手动删除测试向量集合 ' + collection));
}
