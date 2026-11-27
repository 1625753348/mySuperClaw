import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { resolve } from 'node:path';

const [jar] = process.argv.slice(2);
assert.ok(jar && process.env.ENTERPRISE_DB_URL && process.env.ENTERPRISE_DB_PASSWORD, '需要 JAR 和已迁移的独立测试库');
const tenant = 'agent-skills-smoke-' + randomUUID();
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const kid = 'agent-skills-smoke';
const toolName = 'oa_meeting_room_list';
let downstreamCalls = 0;
let modelCalls = 0;
const fixture = createServer(async (req, res) => {
    try {
        if (req.url === '/jwks') {
            return send(res, { keys: [{ ...publicKey.export({ format: 'jwk' }), alg: 'RS256', use: 'sig', kid }] });
        }
        if (req.url === '/rooms') {
            downstreamCalls++;
            return send(res, { rooms: [{ name: '测试会议室', capacity: 12 }] });
        }
        assert.equal(req.url, '/chat/completions');
        modelCalls++;
        let body = '';
        for await (const chunk of req) body += chunk;
        const input = JSON.parse(body);
        const observed = input.messages.filter(message => message.role === 'tool');
        const last = input.messages.at(-1).content;
        const spec = input.tools.map(tool => tool.function.name);
        const user = input.messages.find(message => message.role === 'user').content;
        const unauthorized = user.includes('权限外');
        let action, args, answer;
        switch (observed.length) {
            case 0:
                assert.deepEqual(spec, ['activate_skill']);
                assert.ok(input.messages[0].content.includes('oa-meeting-rooms'));
                assert.ok(!input.messages[0].content.includes('容量相同的会议室'));
                assert.ok(!input.messages[0].content.includes('按参会人数筛选容量'));
                action = 'activate_skill';
                args = { name: 'oa-meeting-rooms' };
                break;
            case 1:
                assert.ok(last.includes('并') || last.includes('保留会议室名称'));
                assert.ok(!last.includes('容量相同的会议室'));
                action = 'read_skill_resource';
                args = { path: unauthorized ? '../../private.md' : 'references/format.md' };
                break;
            case 2:
                if (unauthorized) assert.ok(last.includes('SKILL_ACTION_INVALID'));
                else assert.ok(last.includes('容量相同的会议室'));
                action = 'get_tool_schema';
                args = { name: toolName };
                break;
            case 3:
                assert.ok(last.includes('inputSchema'));
                action = 'execute_tool';
                args = { name: unauthorized ? 'unlisted_tool' : toolName, arguments: {} };
                break;
            default:
                if (unauthorized) assert.ok(last.includes('SKILL_ACTION_INVALID'));
                else assert.ok(last.includes('测试会议室') || last.includes('FORBIDDEN'));
                answer = last.includes('测试会议室') ? '测试会议室容量 12 人' : '调用未授权';
        }
        const message = action ? { role: 'assistant', content: null, tool_calls: [{
            id: 'call_' + observed.length, type: 'function',
            function: { name: action, arguments: JSON.stringify(args) }
        }] } : { role: 'assistant', content: answer };
        send(res, { id: randomUUID(), object: 'chat.completion', model: 'fixture', created: 1,
            choices: [{ index: 0, message, finish_reason: action ? 'tool_calls' : 'stop' }] });
    } catch (error) {
        console.error('模型夹具断言失败', error.message);
        res.writeHead(500).end();
    }
});
function send(res, value) { res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(value)); }
fixture.listen(0, '127.0.0.1');
await once(fixture, 'listening');
const issuer = 'http://127.0.0.1:' + fixture.address().port;
function token(user, scope = 'tools.invoke agent.skills.use') {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const content = encode({ alg: 'RS256', kid }) + '.' + encode({ iss: issuer, aud: 'agent-skills-smoke',
        sub: user, tenant_id: tenant, agent_id: 'test-agent', exp: Math.floor(Date.now() / 1000) + 600, scope });
    return content + '.' + sign('RSA-SHA256', Buffer.from(content), privateKey).toString('base64url');
}
const app = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java',
    ['-jar', jar, '--spring.profiles.active=storage,gateway,agent-skills', '--server.address=127.0.0.1', '--server.port=0'],
    { windowsHide: true, env: { ...process.env, SPRING_APPLICATION_JSON: JSON.stringify({ enterprise: {
        gateway: { jwt: { issuer, audience: 'agent-skills-smoke', 'jwk-set-uri': issuer + '/jwks' },
            targets: { 'test-rooms': issuer + '/rooms' } },
        'agent-skills': { root: resolve('agent-skills'), model: { 'api-key': 'fixture', 'base-url': issuer, name: 'fixture' },
            entries: [{ name: 'oa-meeting-rooms', tenants: [tenant], users: ['alice', 'bob'],
                tools: [{ name: toolName, version: '1' }], resources: ['references/format.md'] }] }
    } }) }, stdio: ['ignore', 'pipe', 'pipe'] });
let logs = '', base;
const closed = once(app, 'close');
const ready = new Promise((resolveReady, reject) => {
    const timeout = setTimeout(() => reject(new Error('Agent Skills API startup timeout')), 60_000);
    function collect(chunk) {
        logs = (logs + chunk.toString()).slice(-96_000);
        const port = logs.match(/Tomcat started on port (\d+)/)?.[1];
        if (port && logs.includes('Started EnterpriseAgentApplication')) {
            clearTimeout(timeout);
            resolveReady('http://127.0.0.1:' + port);
        }
    }
    app.stdout.on('data', collect);
    app.stderr.on('data', collect);
    app.once('exit', code => { clearTimeout(timeout); reject(new Error('Agent Skills API exited ' + code)); });
});

async function api(path, { method = 'GET', actor = token('alice'), body, expected = 200, key = randomUUID() } = {}) {
    const response = await fetch(base + path, { method, headers: { Authorization: 'Bearer ' + actor,
        'Content-Type': 'application/json', 'Idempotency-Key': key },
        body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20_000) });
    if (response.status !== expected) {
        assert.fail(method + ' ' + path + ' returned ' + response.status + ': ' + (await response.text()).slice(0, 1000));
    }
    if (expected < 400 && expected !== 204) return response.json();
}

try {
    base = await ready;
    const admin = token('operator', 'tools.admin');
    const toolPath = '/v1/admin/tools/' + toolName;
    const draft = await api('/v1/admin/tools/drafts', { actor: admin, method: 'POST', expected: 201, body: {
        tool: { name: toolName, description: '会议室查询', riskLevel: 'LOW' },
        version: { version: '1', protocol: 'HTTP', targetRef: 'test-rooms', timeoutMs: 2000,
            inputSchema: { type: 'object', additionalProperties: false },
            outputSchema: { type: 'object', required: ['rooms'] } }
    } });
    await api(toolPath + '/publish', { actor: admin, method: 'POST', body: { version: '1', expectedRevision: draft.revision } });
    for (const [subjectId, action] of [['alice', 'DISCOVER'], ['alice', 'EXECUTE'], ['bob', 'DISCOVER']]) {
        await api(toolPath + '/permissions', { actor: admin, method: 'PUT', expected: 204,
            body: { subjectType: 'USER', subjectId, action, effect: 'ALLOW' } });
    }
    const path = '/v1/agent-skills';
    assert.equal((await api(path))[0].name, 'oa-meeting-rooms');
    assert.deepEqual(await api(path, { actor: token('charlie') }), []);
    await api(path, { actor: token('alice', 'tools.invoke'), expected: 403 });
    await api(path + '/runs', { actor: token('charlie'), method: 'POST', expected: 404,
        body: { message: '会议室列表', skill: 'oa-meeting-rooms' } });
    assert.equal(modelCalls, 0);
    const key = randomUUID();
    const body = { message: '给我查询可以容纳十人的会议室' };
    const run = await api(path + '/runs', { method: 'POST', body, key });
    assert.equal(run.status, 'COMPLETED');
    assert.equal(run.answer, '测试会议室容量 12 人');
    assert.ok(run.executionId);
    assert.deepEqual(await api(path + '/runs', { method: 'POST', body, key }), run);
    await api(path + '/runs', { method: 'POST', body: { message: '另一请求' }, key, expected: 409 });
    await api(path + '/runs/' + run.invocationId, { actor: token('bob'), expected: 404 });
    assert.equal((await api(path + '/runs', { method: 'POST', actor: token('bob'), body })).answer, '调用未授权');
    assert.equal((await api(path + '/runs', { method: 'POST', body: { message: '尝试权限外的操作' } })).answer, '调用未授权');
    assert.equal(downstreamCalls, 1);
    console.log('Agent Skills smoke passed metadata/body/resource disclosure, visibility, gateway authorization, denied unlisted tools and idempotency');
} catch (error) {
    console.error(logs.split(/\r?\n/).filter(line => !/^\s+at /.test(line)).slice(-32).join('\n'));
    throw error;
} finally {
    app.kill();
    await closed;
    fixture.closeAllConnections();
    fixture.close();
}
