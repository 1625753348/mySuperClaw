import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const oaBase = 'http://127.0.0.1:3000';
const credentialRef = 'oa-demo-employee';
const tools = [
    { name: 'oa_reimbursement_list', path: '/api/reimbursements', operation: 'listReimbursements',
        intent: 'EXPENSE.LIST', slot: 'reimbursementStatus', text: '查我的报销单',
        fields: ['id', 'title', 'category', 'amountCents', 'expenseDate', 'status'] },
    { name: 'oa_approval_list', path: '/api/approvals', operation: 'listApprovals',
        intent: 'APPROVAL.LIST', slot: 'approvalStatus', text: '查询待办审批',
        fields: ['id', 'title', 'type', 'status', 'currentStage'] },
    { name: 'oa_meeting_room_list', path: '/api/meeting-rooms', operation: 'listMeetingRooms',
        intent: 'MEETING.ROOM_LIST', text: '查询会议室',
        fields: ['id', 'name', 'floor', 'capacity'] }
];

async function request(base, path, { method = 'GET', token, body, status = 200 } = {}) {
    const headers = { Accept: 'application/json' };
    if (token) headers.Authorization = 'Bearer ' + token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method === 'POST') headers['Idempotency-Key'] = randomUUID();
    const response = await fetch(base + path, { method, headers, redirect: 'error',
        signal: AbortSignal.timeout(15_000), body: body === undefined ? undefined : JSON.stringify(body) });
    assert.equal(response.status, status, method + ' ' + path);
    return status === 204 || status === 404 ? null : response.json();
}

function schemas(spec, tool) {
    const operation = spec.paths[tool.path]?.get;
    assert.equal(operation?.operationId, tool.operation, 'OA 接口契约已变化');
    const status = operation.parameters?.find(parameter => parameter.in === 'query' && parameter.name === 'status');
    assert.equal(!!status, !!tool.slot, 'OA 查询条件已变化');
    const inputSchema = { type: 'object', additionalProperties: false,
        properties: status ? { status: status.schema } : {} };
    const response = operation.responses['200'].content['application/json'].schema;
    assert.equal(response.type, 'array');
    const ref = response.items.$ref.match(/^#\/components\/schemas\/([A-Za-z0-9_]+)$/);
    assert.ok(ref, 'OA 响应必须引用本地 Schema');
    const entity = spec.components.schemas[ref[1]];
    const properties = Object.fromEntries(tool.fields.map(field => {
        assert.ok(entity.required.includes(field) && entity.properties[field], 'OA 输出字段已变化');
        return [field, entity.properties[field]];
    }));
    return { inputSchema, outputSchema: { type: 'array',
        items: { type: 'object', additionalProperties: false, properties, required: tool.fields } } };
}

/** 仅由本机测试启动器显式启用。OA 员工令牌只进入服务进程的凭证配置。 */
export async function prepareOaConsole(config, tenant) {
    const spec = await request(oaBase, '/openapi.json');
    tools.forEach(tool => schemas(spec, tool));
    assert.equal(spec.components.securitySchemes.bearerAuth.scheme, 'bearer');
    const user = await request(oaBase, '/api/auth/me', { token: process.env.PROGRAM001_OA_EMPLOYEE_TOKEN });
    assert.equal(user.role, 'employee', '本机 OA 接入需要员工令牌');
    const gateway = config.enterprise.gateway;
    gateway['http-read-targets'] = { ...gateway['http-read-targets'], ...Object.fromEntries(tools.map(tool => [tool.name, {
        uri: oaBase + tool.path, 'query-parameters': tool.slot ? ['status'] : [], 'response-fields': tool.fields
    }])) };
    gateway.secrets = { ...gateway.secrets, [credentialRef]: { 'bearer-token': process.env.PROGRAM001_OA_EMPLOYEE_TOKEN,
        'allowed-tenants': [tenant], 'allowed-targets': tools.map(tool => 'HTTP:' + tool.name) } };
    config.enterprise.intent.routing.bindings.push(...tools.map(tool => ({
        tenant, intent: tool.intent, mode: 'DIRECT', 'definition-version': 1, 'tool-name': tool.name, 'tool-version': '1',
        parameters: tool.slot ? { '[status]': 'slot.' + tool.slot + '?' } : {}
    })));
    return config;
}

async function publishAndVerify(directory, assistantUser) {
    const metadata = JSON.parse(await readFile(join(directory, 'service.json'), 'utf8'));
    const base = new URL(metadata.url).origin;
    assert.equal(base, 'http://127.0.0.1:8081');
    const admin = (await readFile(join(directory, '管理凭证.txt'), 'utf8')).trim();
    const conversation = (await readFile(join(directory, '会话凭证.txt'), 'utf8')).trim();
    const spec = await request(oaBase, '/openapi.json');

    for (const item of tools) {
        const path = '/v1/admin/tools/' + item.name;
        const response = await fetch(base + path, { headers: { Authorization: 'Bearer ' + admin },
            signal: AbortSignal.timeout(15_000) });
        assert.ok([200, 404].includes(response.status), '无法读取工具目录');
        let tool = response.ok ? (await response.json()).tool : null;
        if (!tool) {
            tool = await request(base, '/v1/admin/tools/drafts', { token: admin, method: 'POST', status: 201,
                body: { tool: { name: item.name, category: 'OA', riskLevel: 'LOW',
                    description: spec.paths[item.path].get.summary },
                version: { version: '1', targetRef: item.name, timeoutMs: 5000, protocol: 'HTTP', credentialRef,
                    ...schemas(spec, item) } }
            });
        }
        if (tool.status !== 'ACTIVE') {
            await request(base, path + '/publish', { token: admin, method: 'POST',
                body: { version: '1', expectedRevision: tool.revision } });
        }
        for (const action of ['DISCOVER', 'EXECUTE']) {
            await request(base, path + '/permissions', { token: admin, method: 'PUT', status: 204,
                body: { subjectType: 'USER', subjectId: assistantUser, action, effect: 'ALLOW' } });
        }
        const hits = await request(base, '/v1/tools/search', { token: conversation, method: 'POST',
            body: { query: item.name, limit: 5 } });
        assert.ok(hits.some(hit => hit.name === item.name), '未召回 OA 工具 ' + item.name);
        const { conversationId } = await request(base, '/v1/conversations', {
            token: conversation, method: 'POST', status: 201
        });
        const turnPath = '/v1/conversations/' + conversationId + '/turns';
        const turn = await request(base, turnPath, { token: conversation, method: 'POST', body: { text: item.text } });
        assert.equal(turn.outcomes[0].intent, item.intent);
        let execution;
        for (let attempt = 0; attempt < 40; attempt++) {
            const receipt = await request(base, turnPath + '/' + turn.turnId, { token: conversation });
            execution = receipt.executions?.[0];
            if (execution && ['SUCCEEDED', 'FAILED', 'BLOCKED', 'UNKNOWN'].includes(execution.status)) break;
            await new Promise(resolve => setTimeout(resolve, 250));
        }
        assert.equal(execution?.status, 'SUCCEEDED', item.name + ' 未完成 ' + (execution?.reason ?? ''));
        assert.ok(Array.isArray(execution.output), item.name + ' 未返回列表');
        assert.ok(execution.output.every(row => Object.keys(row).every(field => item.fields.includes(field))));
        if (item.intent === 'APPROVAL.LIST') assert.equal(turn.outcomes[0].ir.arguments.approvalStatus, 'pending');
        console.log(item.name + ' · ' + item.intent + ' · SUCCEEDED · ' + execution.output.length + ' 条');
    }

    const execute = '/v1/tools/executions';
    const filtered = { toolName: 'oa_reimbursement_list', version: '1', arguments: { status: 'paid' } };
    const paid = await request(base, execute, { method: 'POST', token: conversation, body: filtered });
    assert.equal(paid.status, 'SUCCEEDED');
    assert.ok(Array.isArray(paid.output) && paid.output.every(item => item.status === 'paid'));
    await request(base, execute, { method: 'POST', token: conversation, status: 400,
        body: { ...filtered, arguments: { status: 'paid&status=pending' } } });
    await request(base, execute, { method: 'POST', token: admin, status: 403, body: filtered });
    console.log('报销状态筛选、非法参数和无 EXECUTE 权限主体检查通过');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [directory, assistantUser] = process.argv.slice(2);
    assert.ok(directory && assistantUser, '需要本机控制台凭证目录和获得 OA 权限的会话用户');
    await publishAndVerify(directory, assistantUser);
}
