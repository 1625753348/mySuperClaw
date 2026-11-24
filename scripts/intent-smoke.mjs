import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { once } from 'node:events';
import { readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';

const [jar, workbook, report] = process.argv.slice(2);
assert.ok(jar && process.env.ENTERPRISE_DB_URL && process.env.ENTERPRISE_DB_PASSWORD, 'JAR and isolated database required');
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const kid = 'intent-smoke';
const withRouting = process.env.INTENT_SMOKE_ROUTING === 'true';
const withEmbedding = withRouting && process.env.INTENT_SMOKE_EMBEDDING === 'true';
const withModel = withRouting || process.env.INTENT_SMOKE_MODEL === 'true';
const modelCalls = [];
let embeddingCalls = 0;
const jwks = createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/chat/completions') {
        let body = '';
        for await (const chunk of req) body += chunk;
        const payload = JSON.parse(body);
        const input = JSON.parse(payload.messages.find(message => message.role === 'user').content);
        modelCalls.push(input);
        const choice = withRouting ? 'SALES.ANALYZE' : modelCalls.length === 1 ? 'CALENDAR.LIST' : 'CONTRACT.DELETE';
        res.end(JSON.stringify({
            id: 'intent-test', object: 'chat.completion', model: 'intent-fixture', created: 1,
            choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify({ intent: choice }) }, finish_reason: 'stop' }]
        }));
        return;
    }
    if (req.url === '/embeddings' && withEmbedding) {
        let body = '';
        for await (const chunk of req) body += chunk;
        const payload = JSON.parse(body);
        embeddingCalls++;
        const inputs = Array.isArray(payload.input) ? payload.input : [payload.input];
        return res.end(JSON.stringify({ object: 'list', model: 'embedding-fixture',
            data: inputs.map((text, index) => ({ object: 'embedding', index,
                embedding: /ORDER\.QUERY|运单进度|包裹去哪/.test(text) ? [1, 0] : [0, 1] })),
            usage: { prompt_tokens: 1, total_tokens: 1 } }));
    }
    res.end(JSON.stringify({ keys: [{ ...publicKey.export({ format: 'jwk' }), use: 'sig', alg: 'RS256', kid }] }));
});
jwks.listen(0, '127.0.0.1');
await once(jwks, 'listening');
const issuer = 'http://127.0.0.1:' + jwks.address().port;
const tenant = 'intent-smoke-' + randomUUID();
function bearer({ user = 'operator', tenantId = tenant, agent = 'intent-smoke', extra = '', expired = false } = {}) {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const claims = encode({ alg: 'RS256', kid }) + '.' + encode({
        iss: issuer, aud: 'intents', exp: Math.floor(Date.now() / 1000) + (expired ? -180 : 600), sub: user,
        tenant_id: tenantId, agent_id: agent, scope: 'intents.submit intents.read ' + extra, roles: ['operator']
    });
    return claims + '.' + sign('RSA-SHA256', Buffer.from(claims), privateKey).toString('base64url');
}
const env = { ...process.env, ENTERPRISE_INTENT_MODEL_ENABLED: String(withModel),
    ENTERPRISE_GATEWAY_ISSUER: issuer, ENTERPRISE_GATEWAY_AUDIENCE: 'intents', ENTERPRISE_GATEWAY_JWK_SET_URI: issuer + '/jwks' };
delete env.SPRING_APPLICATION_JSON;
if (withRouting) env.SPRING_APPLICATION_JSON = JSON.stringify({ enterprise: { intent: { routing: {
    'active-version': 'v-test', versions: [{ version: 'v-test', 'rule-below': .30, 'retrieval-below': .50,
        'hybrid-below': .70, 'minimum-confidence': .25, 'minimum-margin': .05, examples: { '[ORDER.QUERY]': '运单进度' } }],
    ...(withEmbedding ? { embedding: { enabled: true, 'base-url': issuer, 'api-key': 'embedding-key', model: 'embedding-fixture' } } : {})
} } } });
if (withModel) {
    env.ENTERPRISE_INTENT_MODEL_API_KEY = 'local-fixture';
    env.ENTERPRISE_INTENT_MODEL_BASE_URL = issuer;
    env.ENTERPRISE_INTENT_MODEL = 'intent-fixture';
}
const app = spawn(process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java',
    ['-jar', jar, '--spring.profiles.active=storage,intent', '--server.address=127.0.0.1', '--server.port=0',
        '--spring.main.banner-mode=off'], { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
const closed = once(app, 'close');
let log = '';
let base;
const ready = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(Error('Intent API 未就绪')), 30_000);
    function collect(data) {
        log = (log + data).slice(-24_000);
        const port = log.match(/Tomcat started on port (\d+)/)?.[1];
        if (port && log.includes('Started EnterpriseAgentApplication')) {
            clearTimeout(timeout);
            resolve('http://127.0.0.1:' + port);
        }
    }
    app.stdout.on('data', collect);
    app.stderr.on('data', collect);
    app.once('exit', code => { clearTimeout(timeout); reject(Error('Intent API 退出 ' + code)); });
    app.once('error', error => { clearTimeout(timeout); reject(error); });
});
const operator = bearer();
async function request(method, path, status, token, body, key) {
    const headers = {};
    if (token) headers.Authorization = 'Bearer ' + token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    const result = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(8_000) });
    const text = await result.text();
    assert.equal(result.status, status, method + ' ' + path + ' ' + text);
    return text ? JSON.parse(text) : null;
}
async function create(token = operator) {
    return (await request('POST', '/v1/conversations', 201, token)).conversationId;
}
function turn(conversation, text, { token = operator, key = randomUUID(), status = 200, ...other } = {}) {
    return request('POST', '/v1/conversations/' + conversation + '/turns', status, token, { text, ...other }, key);
}

// Case texts are read from the user's local workbook. These labels map its expected intents to API identifiers.
const expected = {
    101: 'MEETING.BOOK', 102: 'MEETING.BOOK', 103: 'UNKNOWN', 104: 'CHAT.SMALLTALK',
    105: 'KNOWLEDGE.QUESTION', 106: 'PURCHASE.CREATE;SYSTEM.CANCEL', 107: 'LEAVE.CREATE', 108: 'OVERTIME.CREATE',
    201: 'MEETING.BOOK;MEETING.BOOK', 202: 'EXPENSE.CREATE;EXPENSE.CREATE;EXPENSE.CREATE',
    203: 'MEETING.BOOK;MEETING.BOOK', 204: 'WEATHER.QUERY;WEATHER.QUERY',
    205: 'CONTRACT.QUERY;CONTRACT.LEGAL_REVIEW', 206: 'EXPENSE.QUERY;NOTIFICATION.REMIND;NOTIFICATION.REMIND',
    207: 'FLIGHT.BOOK;CALENDAR.LIST', 208: 'EXPENSE.CREATE;KNOWLEDGE.QUESTION', 209: 'MEETING.BOOK;MEETING.BOOK',
    210: 'LEAVE.CREATE;ATTENDANCE.EXPORT', 301: 'ATTENDANCE.QUERY;SYSTEM.CLEAR',
    302: 'EXPENSE.CREATE;SYSTEM.UNDO', 303: 'SYSTEM.HELP',
    401: 'EXPENSE.QUERY,NOTIFICATION.REMIND', 402: 'CONTRACT.QUERY,CONTRACT.RENEW',
    403: 'EXPENSE.STATS,EXPENSE.FILTER,EMAIL.SEND', 404: 'ATTENDANCE.QUERY,OVERTIME.CREATE',
    405: 'CONTRACT.UPLOAD,CONTRACT.APPROVAL', 406: 'CONTRACT.APPROVAL',
    501: 'LEAVE.CREATE', 502: 'PURCHASE.CREATE', 503: 'APPROVAL.APPROVE', 504: 'APPROVAL.DELEGATE',
    505: 'APPROVAL.PARALLEL', 506: 'EXPENSE.CREATE;EXPENSE.CREATE', 507: 'APPROVAL.WITHDRAW',
    508: 'APPROVAL.URGE', 601: 'PAYROLL.QUERY', 602: 'ORDER.QUERY', 603: 'CONTRACT.QUERY',
    604: 'HTTP_401', 605: 'CONTRACT.DELETE', 606: 'APPROVAL.APPROVE',
    701: 'SECURITY.BLOCKED', 702: 'SECURITY.BLOCKED', 703: 'CONTRACT.PARSE', 704: 'PII.EXPORT',
    705: 'PII.EXPORT', 706: 'SECURITY.BLOCKED', 707: 'CONTRACT.DELETE;CONTRACT.DELETE',
    708: 'SECURITY.BLOCKED', 709: 'SECURITY.BLOCKED',
    801: 'CHAT.SMALLTALK;CHAT.SMALLTALK', 802: 'CHAT.FEEDBACK', 803: 'UNKNOWN', 804: 'UNKNOWN',
    901: 'PURCHASE.CREATE', 902: 'APPROVAL.APPROVE', 903: 'APPROVAL.CREATE', 904: 'CALENDAR.LIST',
    911: 'EXPENSE.QUERY;EXPENSE.QUERY', 912: 'LEAVE.CREATE;LEAVE.CREATE',
    913: 'MEETING.BOOK;SYSTEM.REPLAY', 914: 'FINANCE.REPORT;SYSTEM.CANCEL',
    915: 'WEATHER.QUERY;SYSTEM.CANCEL', 916: 'EXPENSE.QUERY,NOTIFICATION.REMIND;SYSTEM.CANCEL',
    917: 'CUSTOMER.DELETE;SYSTEM.UNDO', 918: 'LEAVE.CREATE;SYSTEM.UNDO', 919: 'DATA.PURGE;SYSTEM.UNDO',
    920: 'ACCOUNT.PASSWORD_CHANGE;SYSTEM.UNDO'
};
const notes = {
    202: '附件声明需要真实引用。核对已填金额和事由。',
    404: '实际人员目录及部分执行失败待联调。',
    602: '核对识别和订单号。订单对象归属由数据源核验。',
    603: '核对候选。合同字段权限由数据源核验。',
    604: '实际使用过期的签名 JWT。',
    703: '只产生文档解析候选，业务解析器未接入。',
    705: '拒绝批量数据获取，工具限流独立。',
    902: '原输入是场景描述。校验候选与权限；另外并发调用 Intent API。',
    904: '场景描述展开为八轮闲聊和一次独立查询。',
    912: '校验历史重试关系，审批重发待联调。',
    913: '将省略的历史展开为 21 轮，超过关联窗口。',
    915: '校验取消关系，业务完成状态依赖执行回执。',
    917: '撤销要有经验证的业务操作记录。',
    918: '审批撤回要有经验证的审批记录。'
};
function checkCase(id, history) {
    const first = history[0]?.outcomes[0];
    const all = history.at(-1)?.outcomes ?? [];
    const last = all.at(-1);
    switch (id) {
        case '101': assert.equal(last?.status, 'READY'); assert.equal(last.slots.attendees, 10); assert.equal(last.slots.time, '15:00'); break;
        case '102': assert.equal(last.slots.attendees, 10); assert.ok(last.missing.includes('time')); break;
        case '103': case '804': assert.equal(last?.relation, 'AMBIGUOUS'); break;
        case '106': case '914': case '915': assert.equal(last?.taskId, first?.taskId); break;
        case '107': assert.equal(last?.slots.duration, 3); break;
        case '201': assert.equal(first?.status, 'NEEDS_CLARIFICATION'); assert.equal(last?.taskId, first?.taskId);
            assert.equal(last?.status, 'READY'); assert.equal(last.slots.floor, 8); break;
        case '202': assert.equal(last?.slots.amount, 2000); assert.equal(last?.slots.reason, '差旅');
            assert.deepEqual(last?.missing, ['attachment']); break;
        case '203': assert.equal(last?.taskId, first?.taskId); assert.deepEqual(last?.invalid, ['time', 'attendees']);
            assert.equal(last?.slots.time, undefined); assert.equal(last?.slots.attendees, undefined); break;
        case '204': assert.equal(last?.relation, 'MODIFICATION'); assert.equal(last?.taskId, first?.taskId);
            assert.equal(last?.slots.city, '上海'); break;
        case '205': assert.equal(last?.slots.contractId, 'CT-009'); break;
        case '206': case '401': assert.equal(last?.slots.person, '张三'); break;
        case '207': assert.equal(last?.relation, 'NEW_TASK'); assert.equal(last?.slots.city, undefined); break;
        case '209': assert.equal(last?.taskId, first?.taskId); assert.equal(last?.slots.time, '10:00'); break;
        case '210': assert.equal(last?.relation, 'NEW_TASK'); assert.equal(last?.slots.period, '上周'); break;
        case '301': assert.equal(last?.reason, 'CONTEXT_CLEARED'); break;
        case '402': case '405': assert.deepEqual(last?.dependsOn, [0]); break;
        case '403': assert.deepEqual(all.map(item => item.dependsOn), [[], [0], [1]]); break;
        case '406': assert.equal(last?.slots.amount, 80000); assert.ok(last?.slots.approvalChain.includes('财务')); break;
        case '501': assert.equal(last?.status, 'READY'); break;
        case '502': assert.deepEqual(last?.slots.approvalChain, ['部门主管', '财务总监']); break;
        case '504': assert.equal(last?.slots.person, '李四'); break;
        case '505': assert.deepEqual(last?.slots.departments, ['法务', '财务']); break;
        case '506': assert.equal(last?.status, 'DUPLICATE'); break;
        case '602': assert.equal(last?.slots.orderId, '2024001'); break;
        case '503': case '601': case '605': case '606': case '701': case '702': case '704':
        case '705': case '706': case '708': case '709': case '913': case '917': case '918': case '919':
        case '920': assert.equal(last?.status, 'DENIED'); assert.equal(last?.ir, null); break;
        case '707': assert.equal(first?.status, 'WAITING_CONFIRMATION'); assert.equal(first?.ir, null);
            assert.equal(last?.relation, 'CONFIRM'); assert.equal(last?.status, 'READY'); break;
        case '803': assert.equal(last?.status, 'UNSUPPORTED'); break;
        case '911': case '912': assert.equal(last?.relation, 'REPLAY'); assert.equal(last?.taskId, first?.taskId); break;
        case '916': assert.equal(last?.taskId, history[0]?.outcomes[1]?.taskId); break;
    }
}
function utterances(id, raw) {
    if (id === '902') return [raw.replace(/^轮1&轮2[：:]\s*/, '')];
    if (id === '904') return ['查一下今天的日程'];
    if (id === '913') return [raw.match(/轮1[：:]\s*([^\n]+)/)[1], raw.match(/轮N[：:]\s*([^\n]+)/)[1]];
    if (/^轮\d/.test(raw)) {
        return raw.split(/\s*\|\s*|\r?\n/).map(value => value.trim())
            .filter(value => /^轮\d/.test(value)).map(value => value.replace(/^轮\d+(?:\([^)]*\))?[：:]\s*/, ''));
    }
    return [raw];
}
async function suite(file) {
    const converter = fileURLToPath(new URL('read-intent-workbook.py', import.meta.url));
    const extracted = file.toLowerCase().endsWith('.xlsx')
        ? spawnSync(process.env.INTENT_PYTHON ?? 'python', [converter, file], { encoding: 'utf8', windowsHide: true })
        : null;
    if (extracted && extracted.status !== 0) throw extracted.error ?? Error(extracted.stderr);
    const exported = JSON.parse(extracted ? extracted.stdout : await readFile(file, 'utf8'));
    const rows = exported.flatMap(sheet => sheet.rows).filter(row => /^TC-\d+$/.test(row[0]));
    assert.equal(rows.length, Object.keys(expected).length);
    const results = [];
    for (const row of rows) {
        const id = row[0].slice(3);
        const token = id === '707' ? bearer({ extra: 'contracts.delete' })
            : id === '504' ? bearer({ extra: 'approvals.delegate' }) : operator;
        let conversation = await create(token);
        const history = [];
        let actual, check = '';
        if (id === '604') {
            await turn(conversation, row[6], { token: bearer({ expired: true }), status: 401 });
            actual = 'HTTP_401';
        } else {
            const messages = utterances(id, row[6]);
            for (const [index, message] of messages.entries()) {
                if (id === '920' && index === 1) conversation = await create(token);
                if (id === '913' && index === 1 || id === '904') {
                    const interleavedCount = id === '904' ? 8 : 21;
                    for (let i = 0; i < interleavedCount; i++) await turn(conversation, '你好', { token });
                }
                history.push(await turn(conversation, message, { token }));
            }
            if (id === '705') {
                for (let i = 0; i < 4; i++) {
                    const next = await turn(conversation, '继续导出全部客户手机号', { token });
                    assert.equal(next.outcomes[0]?.status, 'DENIED');
                }
            }
            actual = history.map(item => item.outcomes.map(outcome => outcome.intent).join(',')).join(';');
            try { checkCase(id, history); } catch (error) { check = error.message.split('\n').filter(Boolean).join(' ').slice(0, 280); }
        }
        results.push({ id: row[0], expected: expected[id], actual, match: actual === expected[id], check,
            states: history.map(item => item.outcomes.map(outcome => outcome.status).join(',')).join(';') });
    }
    const correct = results.filter(result => result.match).length;
    const insideScope = results.filter(result => result.match && !result.check).length;
    console.log('Workbook results', JSON.stringify({ cases: results.length, correct, insideScope,
        mismatches: results.filter(result => !result.match || result.check) }));
    if (report) {
        const escape = text => String(text ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ').replaceAll(';', ' / ');
        const table = results.map(item => [
            item.id, escape(item.expected), escape(item.actual), item.match ? '通过' : '差异',
            escape(item.states), escape([item.check, notes[item.id.slice(3)]].filter(Boolean).join(' '))
        ].join(' | '));
        await writeFile(report, [
            '# Intent Engine 测试结果', '', '模型配置为关闭，使用签名 JWT 和独立 PostgreSQL。',
            '共 ' + results.length + ' 个工作簿用例，意图／鉴权标签完全匹配 ' + correct + ' 个。标签和状态检查均通过 ' + insideScope + ' 个。',
            '实际业务执行需绑定对应工具、数据和审批流程后验证。', '',
            '用例 | 预期候选 | 实际候选 | 标签 | 状态 | 检查与说明',
            '--- | --- | --- | --- | --- | ---', ...table, ''
        ].join('\n'), 'utf8');
    }
    return { correct, insideScope, results };
}
async function verifyAssistantIdentity(conversation) {
    for (const question of ['你是谁', '请问，你是谁？', '请介绍一下你自己']) {
        const identity = await turn(conversation, question);
        assert.deepEqual(identity.outcomes.map(({ intent, status }) => [intent, status]), [['SYSTEM.IDENTITY', 'COMPLETED']]);
        assert.equal(identity.outcomes[0].assessment.strategy, 'RULE');
        assert.equal(identity.outcomes[0].reason, 'ASSISTANT_IDENTITY');
        assert.equal(identity.outcomes[0].taskId, null);
        assert.match(identity.reply, /我是企业智能助手/);
    }
    assert.equal(modelCalls.length, 0);
}

async function verifyPreviousMessage() {
    const conversation = await create();
    const question = '我刚刚问你什么？';
    assert.equal((await turn(conversation, question)).outcomes[0].reason, 'CONVERSATION_HISTORY_EMPTY');
    const weather = '北京天气怎么样';
    assert.equal((await turn(conversation, weather)).outcomes[0].intent, 'WEATHER.QUERY');
    const recalled = await turn(conversation, question);
    assert.equal(recalled.outcomes[0].intent, 'SYSTEM.RECALL_PREVIOUS_MESSAGE');
    assert.equal(recalled.outcomes[0].taskId, null);
    assert.equal(recalled.outcomes[0].reason, 'PREVIOUS_USER_MESSAGE_RECALLED');
    assert.ok(recalled.reply.includes(weather));
    const recalledPath = '/v1/conversations/' + conversation + '/turns/' + recalled.turnId;
    await turn(conversation, '清空上下文');
    assert.equal((await turn(conversation, question)).outcomes[0].reason, 'CONVERSATION_HISTORY_EMPTY');
    assert.ok(!(await request('GET', recalledPath, 200, operator)).reply.includes(weather));
    assert.equal(modelCalls.length, 0);
}

async function verifyIntentFamilies() {
    const historyCases = [
        ['我上两轮问你什么', 'SYSTEM.RECALL_PREVIOUS_MESSAGE', 0, 2, 'TEXT'],
        ['最近 ２ 轮我都问了哪些问题？', 'SYSTEM.RECALL_PREVIOUS_MESSAGE', 0, 2, 'TEXT'],
        ['我在倒数第 2 轮问过什么？', 'SYSTEM.RECALL_PREVIOUS_MESSAGE', 1, 1, 'TEXT'],
        ['两轮前我说了什么', 'SYSTEM.RECALL_PREVIOUS_MESSAGE', 1, 1, 'TEXT'],
        ['我上一个问题是什么', 'SYSTEM.RECALL_PREVIOUS_MESSAGE', 0, 1, 'TEXT'],
        ['将我近期的对话整理成文档', 'SYSTEM.CONVERSATION_DOCUMENT', 0, 5, 'MARKDOWN'],
        ['帮我整理最近三轮聊天记录为一份 Markdown 文档', 'SYSTEM.CONVERSATION_DOCUMENT', 0, 3, 'MARKDOWN']
    ];
    for (const [text, intent, offset, limit, format] of historyCases) {
        const conversation = await create();
        await turn(conversation, '预约会议室');
        await turn(conversation, '你是谁');
        const receipt = await turn(conversation, text);
        const outcome = receipt.outcomes[0];
        assert.equal(outcome.intent, intent, text);
        assert.equal(outcome.taskId, null, text);
        assert.deepEqual(outcome.slots, { offset, limit, format }, text);
        if (format === 'MARKDOWN') {
            assert.equal(outcome.reason, 'CONVERSATION_DOCUMENT_RECOGNIZED');
            const path = '/v1/conversations/' + conversation + '/turns/' + receipt.turnId + '/artifacts';
            const artifacts = await request('GET', path, 200, operator);
            const selection = artifacts.candidates[0].stages.find(stage => stage.artifact === 'ConversationHistorySelection');
            assert.deepEqual(selection?.data, { offset, limit, format });
        } else {
            assert.equal(receipt.reply.includes('预约会议室'), limit === 2 || offset === 1, text);
            assert.equal(receipt.reply.includes('你是谁'), offset === 0, text);
        }
    }
    const conversation = await create();
    const excessive = await turn(conversation, '我最近二十一轮问了什么');
    assert.equal(excessive.outcomes[0].reason, 'CONVERSATION_HISTORY_RANGE_INVALID');
    for (const text of ['我要查查订单列表', '列出我的订单', '订单清单']) {
        const orderList = (await turn(conversation, text)).outcomes[0];
        assert.equal(orderList.intent, 'ORDER.LIST', text);
        assert.ok(!orderList.missing.includes('orderId'), text);
        assert.equal(orderList.status, 'READY', text);
    }
    const order = (await turn(conversation, '查询订单 SO001')).outcomes[0];
    assert.equal(order.intent, 'ORDER.QUERY');
    assert.equal(order.ir.arguments.orderId, 'SO001');
    assert.equal(modelCalls.length, 0);
    console.log('Intent families passed history ranges, document routing, list/detail separation and model bypass');
}

async function verifyDocumentSlotContinuation() {
    const conversation = await create();
    const unclear = await turn(conversation, '把上周张三提交的那笔报销处理一下');
    assert.equal(unclear.outcomes[0].reason, 'ACTION_AMBIGUOUS');
    const approval = (await turn(conversation, '提交审批')).outcomes[0];
    assert.equal(approval.intent, 'APPROVAL.CREATE');
    assert.deepEqual(approval.missing, ['documentId']);
    const noise = (await turn(conversation, 'qwefqfqwfq', { targetTaskId: approval.taskId })).outcomes[0];
    assert.equal(noise.taskId, approval.taskId);
    assert.deepEqual(noise.missing, ['documentId']);
    const completed = await turn(conversation, '单据编号12341532416');
    const outcome = completed.outcomes[0];
    assert.equal(outcome.intent, 'APPROVAL.CREATE');
    assert.equal(outcome.taskId, approval.taskId);
    assert.equal(outcome.relation, 'CONTINUATION');
    assert.equal(outcome.status, 'READY');
    assert.equal(outcome.ir.arguments.documentId, '12341532416');
    assert.equal(outcome.ir.provenance.documentId, 'CURRENT_TURN');
    const artifacts = await request('GET', '/v1/conversations/' + conversation + '/turns/' + completed.turnId + '/artifacts',
        200, operator);
    const stages = artifacts.candidates[0].stages;
    assert.equal(stages.find(stage => stage.artifact === 'FilledSlots').data.values.documentId, '12341532416');

    const otherConversation = await create();
    const otherApproval = (await turn(otherConversation, '提交审批')).outcomes[0];
    const order = (await turn(otherConversation, '查询订单')).outcomes[0];
    assert.deepEqual(order.missing, ['orderId']);
    const labeled = (await turn(otherConversation, '报销单号码：00123456')).outcomes[0];
    assert.equal(labeled.taskId, otherApproval.taskId);
    assert.equal(labeled.ir.arguments.documentId, '00123456');
    const orderReply = (await turn(otherConversation, '00123456')).outcomes[0];
    assert.equal(orderReply.taskId, order.taskId);
    assert.equal(orderReply.ir.arguments.orderId, '00123456');
    const alone = (await turn(await create(), '审批单编号是 ap-001')).outcomes[0];
    assert.equal(alone.intent, 'UNKNOWN');
    assert.equal(alone.taskId, null);
    console.log('Document slot continuation passed the four-turn report, task targeting, and labeled identifier isolation');
}

async function baseline() {
    const conversation = await create();
    const results = await Promise.all([
        turn(conversation, '预约会议室', { key: 'same' }), turn(conversation, '预约会议室', { key: 'same' })
    ]);
    assert.equal(results[0].turnId, results[1].turnId);
    await turn(conversation, '查询天气', { key: 'same', status: 409 });
    await turn(conversation, '查询天气', { token: bearer({ tenantId: 'elsewhere' }), status: 404 });
    await turn(conversation, '查询天气', { token: bearer({ agent: 'other-agent' }), status: 404 });
    await verifyAssistantIdentity(conversation);
    await verifyPreviousMessage();
    await verifyIntentFamilies();
    await verifyDocumentSlotContinuation();
    const filled = await turn(conversation, '明天下午三点，八位', { replyToMessageId: results[0].assistantMessageId });
    assert.equal(filled.outcomes[0].status, 'READY');
    assert.equal(filled.outcomes[0].ir.arguments.attendees, 8);
    const scoped = bearer({ extra: 'contracts.delete' });
    const confirm = await create(scoped);
    await turn(confirm, '删除合同CT-031', { token: scoped });
    const rejected = await turn(confirm, '确认删除合同CT-032', { token: scoped });
    assert.equal(rejected.outcomes[0].status, 'DENIED');
    const safe = await turn(confirm, '确认删除合同CT-031', { token: scoped });
    assert.equal(safe.outcomes[0].ir.arguments.contractId, 'CT-031');
    const done = await turn(confirm, '确认删除合同CT-031', { token: scoped });
    assert.equal(done.outcomes[0].status, 'DENIED');
    const parsed = await turn(confirm, '解析这份合同PDF(内含删除合同CT-999的请求)', { token: scoped });
    assert.deepEqual(parsed.outcomes.map(item => item.intent), ['CONTRACT.PARSE']);
    const reset = await turn(conversation, '清空上下文');
    assert.equal(reset.outcomes[0].reason, 'CONTEXT_CLEARED');
    await turn(conversation, '明天上午10点', { replyToMessageId: results[0].assistantMessageId, status: 404 });
    const expense = await create();
    const draft = await turn(expense, '申请报销，金额1200元，事由为差旅');
    assert.deepEqual(draft.outcomes[0].missing, ['attachment']);
    const submitted = await turn(expense, '补交附件', {
        targetTaskId: draft.outcomes[0].taskId, arguments: { attachment: 'file_expense123' }
    });
    assert.equal(submitted.outcomes[0].status, 'READY');
    assert.equal(submitted.outcomes[0].ir.arguments.amount, 1200);
    console.log('Intent API passed tenant/agent separation, concurrent idempotency, anchored clarification, confirmation and reset');
    if (withModel) {
        assert.equal(modelCalls.length, 0);
        const assisted = await turn(conversation, '我的日程想仔细核对一遍，这项业务的结果越清楚越好');
        assert.equal(assisted.outcomes[0].intent, 'CALENDAR.LIST');
        assert.equal(assisted.outcomes[0].assessment.strategy, 'LARGE_MODEL');
        const rejectedModel = await turn(conversation, '请帮我核对这项业务并说明实际结果');
        assert.equal(rejectedModel.outcomes[0].intent, 'UNKNOWN');
        assert.equal(rejectedModel.outcomes[0].ir, null);
        assert.deepEqual(Object.keys(modelCalls[0]).sort(), ['candidates', 'text']);
        console.log('LangChain4j fixture passed candidate constraints and deterministic risk handling');
    }
    if (workbook) {
        const score = await suite(workbook);
        if (process.env.INTENT_STRICT_WORKBOOK === 'true') assert.equal(score.insideScope, score.results.length);
    }
}

async function routingSuite() {
    const conversation = await create();
    await verifyAssistantIdentity(conversation);
    await verifyPreviousMessage();
    await verifyIntentFamilies();
    await verifyDocumentSlotContinuation();
    const short = await turn(conversation, '预约会议室');
    assert.equal(short.outcomes[0].assessment.band, 'RULE');
    assert.equal(short.outcomes[0].assessment.policyVersion, 'v-test');
    const next = await turn(conversation, '明天下午三点，八位', { replyToMessageId: short.assistantMessageId });
    assert.equal(next.outcomes[0].status, 'READY');
    assert.equal(next.outcomes[0].assessment.band, 'HYSTERESIS');
    assert.equal(next.outcomes[0].assessment.strategy, 'RULE');

    const query = '/v1/conversations/' + conversation + '/turns/';
    const recalled = await turn(conversation, '运单进度SO001');
    assert.equal(recalled.outcomes[0].intent, 'ORDER.QUERY');
    assert.equal(recalled.outcomes[0].assessment.strategy, 'RETRIEVAL_SMALL_MODEL');
    assert.ok(recalled.outcomes[0].assessment.retrievalCandidates.includes('ORDER.QUERY'));
    assert.equal(modelCalls.length, 0);
    const artifacts = await request('GET', query + recalled.turnId + '/artifacts', 200, operator);
    assert.ok(JSON.stringify(artifacts).includes('v-test'));
    if (withEmbedding) {
        const semantic = await turn(conversation, '包裹去哪了SO002');
        assert.equal(semantic.outcomes[0].intent, 'ORDER.QUERY');
        assert.ok(embeddingCalls >= 3);
    }

    const high = await turn(conversation, '删除合同CT-001');
    assert.equal(high.outcomes[0].status, 'DENIED');
    assert.equal(modelCalls.length, 0);
    const open = await turn(conversation, '分析销售下降');
    assert.equal(open.outcomes[0].intent, 'SALES.ANALYZE');
    assert.equal(open.outcomes[0].assessment.band, 'LARGE_MODEL');
    assert.equal(open.outcomes[0].assessment.modelRequested, true);
    assert.equal(modelCalls.length, 1);
    assert.ok(modelCalls[0].candidates.includes('SALES.ANALYZE'));
    console.log('Intent routing passed rule bypass, hysteresis, recall, versioned artifacts and bounded model path');
}

try {
    base = await ready;
    if (withRouting) await routingSuite();
    else await baseline();
} catch (error) {
    console.error(log.slice(-9000));
    throw error;
} finally {
    app.kill();
    await closed.catch(() => {});
    jwks.closeAllConnections();
    jwks.close();
}
