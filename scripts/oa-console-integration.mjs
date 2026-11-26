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
const createTools = [
    { name: 'oa_meeting_draft_create', path: '/api/meetings', operation: 'createMeeting',
        intent: 'MEETING.DRAFT_CREATE', text: '创建会议草稿', risk: 'MEDIUM', flags: { draft: true },
        slots: { title: 'oaTitle', attendeeCount: 'oaAttendeeCount' },
        fields: ['id', 'title', 'status', 'expiresAt'], expectedStatus: 'draft', cleanupAction: 'cancel' },
    { name: 'oa_reimbursement_submit', path: '/api/reimbursements', operation: 'submitReimbursement',
        intent: 'EXPENSE.OA_SUBMIT', text: '提交OA报销', risk: 'HIGH', idempotencyField: 'requestKey',
        slots: { title: 'oaTitle', category: 'oaCategory', amountCents: 'oaExpenseAmount',
            expenseDate: 'oaExpenseDate', description: 'oaDescription' },
        fields: ['id', 'title', 'status', 'amountCents'], expectedStatus: 'pending', cleanupAction: 'withdraw' }
];

async function request(base, path, { method = 'GET', token, body, status = 200, key = randomUUID() } = {}) {
    const headers = { Accept: 'application/json' };
    if (token) headers.Authorization = 'Bearer ' + token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method === 'POST') headers['Idempotency-Key'] = key;
    const response = await fetch(base + path, { method, headers, redirect: 'error',
        signal: AbortSignal.timeout(15_000), body: body === undefined ? undefined : JSON.stringify(body) });
    assert.equal(response.status, status, method + ' ' + path);
    return status === 204 || status >= 400 ? null : response.json();
}

function schemas(spec, tool) {
    const operation = spec.paths[tool.path]?.[tool.slots ? 'post' : 'get'];
    assert.equal(operation?.operationId, tool.operation, 'OA 接口契约已变化');
    if (tool.slots) {
        const upstream = operation.requestBody.content['application/json'].schema;
        const required = Object.keys(tool.slots);
        assert.ok(upstream.required.every(field => required.includes(field)), 'OA 写入出现未映射必填参数');
        for (const fixed of [...Object.keys(tool.flags ?? {}), tool.idempotencyField].filter(Boolean)) {
            assert.ok(upstream.properties[fixed], 'OA 固定参数不在契约中');
        }
        const inputSchema = { type: 'object', additionalProperties: false, required,
            properties: selectedProperties(upstream, required) };
        const response = operation.responses['201'].content['application/json'].schema;
        const entity = localEntity(spec, response.$ref);
        const outputSchema = { type: 'object', additionalProperties: false, required: tool.fields,
            properties: selectedProperties(entity, tool.fields) };
        return { inputSchema, outputSchema };
    }
    const status = operation.parameters?.find(parameter => parameter.in === 'query' && parameter.name === 'status');
    assert.equal(!!status, !!tool.slot, 'OA 查询条件已变化');
    const inputSchema = { type: 'object', additionalProperties: false,
        properties: status ? { status: status.schema } : {} };
    const response = operation.responses['200'].content['application/json'].schema;
    assert.equal(response.type, 'array');
    const entity = localEntity(spec, response.items.$ref);
    return { inputSchema, outputSchema: { type: 'array',
        items: { type: 'object', additionalProperties: false, properties: selectedProperties(entity, tool.fields),
            required: tool.fields } } };
}

function localEntity(spec, reference) {
    const ref = reference?.match(/^#\/components\/schemas\/([A-Za-z0-9_]+)$/);
    assert.ok(ref, 'OA 响应必须引用本地 Schema');
    return spec.components.schemas[ref[1]];
}

function selectedProperties(schema, fields) {
    return Object.fromEntries(fields.map(field => {
        assert.ok(schema.properties[field], 'OA 契约字段已变化');
        return [field, schema.properties[field]];
    }));
}

/** 仅由本机测试启动器显式启用。OA 员工令牌只进入服务进程的凭证配置。 */
export async function prepareOaConsole(config, tenant) {
    const spec = await request(oaBase, '/openapi.json');
    const enabled = [...tools, ...createTools];
    enabled.forEach(tool => schemas(spec, tool));
    assert.equal(spec.components.securitySchemes.bearerAuth.scheme, 'bearer');
    const user = await request(oaBase, '/api/auth/me', { token: process.env.PROGRAM001_OA_EMPLOYEE_TOKEN });
    assert.equal(user.role, 'employee', '本机 OA 接入需要员工令牌');
    const gateway = config.enterprise.gateway;
    gateway['http-read-targets'] = { ...gateway['http-read-targets'], ...Object.fromEntries(tools.map(tool => [tool.name, {
        uri: oaBase + tool.path, 'query-parameters': tool.slot ? ['status'] : [], 'response-fields': tool.fields
    }])) };
    gateway['http-write-targets'] = { ...gateway['http-write-targets'],
        ...Object.fromEntries(createTools.map(tool => [tool.name, { uri: oaBase + tool.path,
            'fixed-flags': tool.flags, 'idempotency-field': tool.idempotencyField, 'response-fields': tool.fields }])) };
    gateway.secrets = { ...gateway.secrets, [credentialRef]: { 'bearer-token': process.env.PROGRAM001_OA_EMPLOYEE_TOKEN,
        'allowed-tenants': [tenant], 'allowed-targets': enabled.map(tool => 'HTTP:' + tool.name) } };
    config.enterprise.intent.routing.bindings.push(...enabled.map(tool => ({
        tenant, intent: tool.intent, mode: 'DIRECT', 'definition-version': 1, 'tool-name': tool.name, 'tool-version': '1',
        parameters: tool.slots ? Object.fromEntries(Object.entries(tool.slots).map(([key, slot]) => ['[' + key + ']', 'slot.' + slot]))
            : tool.slot ? { '[status]': 'slot.' + tool.slot + '?' } : {}
    })));
    return config;
}

async function publishAndVerify(directory, assistantUser, verifyCreates) {
    const metadata = JSON.parse(await readFile(join(directory, 'service.json'), 'utf8'));
    const base = new URL(metadata.url).origin;
    assert.equal(base, 'http://127.0.0.1:8081');
    const admin = (await readFile(join(directory, '管理凭证.txt'), 'utf8')).trim();
    const conversation = (await readFile(join(directory, '会话凭证.txt'), 'utf8')).trim();
    const spec = await request(oaBase, '/openapi.json');

    for (const item of [...tools, ...createTools]) {
        const path = '/v1/admin/tools/' + item.name;
        const response = await fetch(base + path, { headers: { Authorization: 'Bearer ' + admin },
            signal: AbortSignal.timeout(15_000) });
        assert.ok([200, 404].includes(response.status), '无法读取工具目录');
        let tool = response.ok ? (await response.json()).tool : null;
        if (!tool) {
            tool = await request(base, '/v1/admin/tools/drafts', { token: admin, method: 'POST', status: 201,
                body: { tool: { name: item.name, category: 'OA', riskLevel: item.risk ?? 'LOW',
                    description: item.text },
                version: { version: '1', targetRef: item.name, timeoutMs: 5000, protocol: 'HTTP', credentialRef,
                    idempotencySupported: !!item.idempotencyField,
                    ...schemas(spec, item) } }
            });
        }
        if (item.risk === 'HIGH') {
            const policies = await request(base, path + '/policies', { token: admin });
            if (!policies.some(policy => policy.kind === 'APPROVAL')) {
                await request(base, path + '/policies/APPROVAL', { method: 'PUT', token: admin,
                    body: { expectedVersion: 0, rule: { ttlSeconds: 900 } } });
            }
            const subjectId = JSON.parse(Buffer.from(admin.split('.')[1], 'base64url')).sub;
            await request(base, path + '/permissions', { token: admin, method: 'PUT', status: 204,
                body: { subjectType: 'USER', subjectId, action: 'APPROVE', effect: 'ALLOW' } });
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
        if (item.slots) {
            assert.equal(turn.outcomes[0].status, 'NEEDS_CLARIFICATION');
            assert.equal(turn.executions.length, 0);
            if (verifyCreates) await verifyCreate({ base, turnPath, turn, tool: item, conversation, admin });
            else console.log(item.name + ' · NEEDS_CLARIFICATION · 未调用 OA 写入');
            continue;
        }
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

async function verifyCreate({ base, turnPath, turn, tool, conversation, admin }) {
    const oaToken = process.env.PROGRAM001_OA_EMPLOYEE_TOKEN;
    assert.ok(oaToken, '验证创建和清理需要本机 OA 员工令牌');
    const meeting = tool.intent === 'MEETING.DRAFT_CREATE';
    const marker = 'OA联调测试-' + randomUUID();
    const firstTask = turn.outcomes[0].taskId;
    const first = await request(base, turnPath, { token: conversation, method: 'POST',
        body: { text: '标题：' + marker, replyToMessageId: turn.assistantMessageId } });
    assert.equal(first.outcomes[0].taskId, firstTask);
    assert.equal(first.outcomes[0].status, 'NEEDS_CLARIFICATION');
    assert.equal(first.executions.length, 0);
    const key = randomUUID();
    const body = { text: meeting ? '8人' : '类别：交通，金额：12.34元，费用日期：今天，说明：本机联调测试',
        replyToMessageId: first.assistantMessageId };
    const completed = await request(base, turnPath, { token: conversation, method: 'POST', body, key });
    assert.equal(completed.outcomes[0].status, 'READY');
    assert.equal(completed.outcomes[0].taskId, firstTask);
    if (!meeting) assert.equal(completed.outcomes[0].ir.arguments.oaExpenseAmount, 1234);
    const replay = await request(base, turnPath, { token: conversation, method: 'POST', body, key });
    assert.equal(replay.turnId, completed.turnId);
    const receiptPath = turnPath + '/' + completed.turnId;
    if (!meeting) {
        const paused = await request(base, receiptPath, { token: conversation });
        const waiting = paused.executions[0];
        assert.equal(waiting.status, 'WAITING_APPROVAL');
        assert.ok(waiting.approvalId);
        const upstream = await request(oaBase, tool.path, { token: oaToken });
        assert.ok(!upstream.some(item => item.title === marker), '审批前 OA 不应出现报销单');
        const approvalPath = '/v1/tools/approvals/' + waiting.approvalId + '/decisions';
        await request(base, approvalPath, { method: 'POST', token: conversation, status: 403, body: { decision: 'APPROVE' } });
        await request(base, approvalPath, { method: 'POST', token: admin, body: { decision: 'APPROVE' } });
    }
    let execution;
    for (let attempt = 0; attempt < 40; attempt++) {
        const receipt = await request(base, receiptPath, { token: conversation });
        execution = receipt.executions[0];
        if (execution && ['SUCCEEDED', 'FAILED', 'BLOCKED', 'UNKNOWN'].includes(execution.status)) break;
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert.equal(execution?.status, 'SUCCEEDED', tool.name + '：' + (execution?.reason ?? ''));
    const saved = execution.output;
    try {
        assert.equal(saved.title, marker);
        assert.equal(saved.status, tool.expectedStatus);
        const upstream = await request(oaBase, tool.path, { token: oaToken });
        assert.deepEqual(upstream.filter(item => item.title === marker).map(item => item.id), [saved.id]);
    } finally {
        if (saved?.id && saved.title === marker) {
            await request(oaBase, tool.path + '/' + encodeURIComponent(saved.id) + '/' + tool.cleanupAction,
                { method: 'POST', token: oaToken });
        }
    }
    console.log(tool.name + ' · 多轮补参、重复提交与 OA 回执验证通过 · 测试单已清理');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [directory, assistantUser, mode] = process.argv.slice(2);
    assert.ok(directory && assistantUser, '需要本机控制台凭证目录和获得 OA 权限的会话用户');
    await publishAndVerify(directory, assistantUser, mode === '--verify-creates');
}
