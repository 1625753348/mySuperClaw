(() => {
    const conversations = new Map();
    const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'BLOCKED',
        'UNKNOWN', 'NEEDS_RECONCILIATION']);
    const labels = {
        NEEDS_CLARIFICATION: '等待补参', WAITING_CONFIRMATION: '等待确认', WAITING_DEPENDENCY: '等待依赖',
        READY: '意图已校验', QUEUED: '已排队', RUNNING: '执行中', WAITING_APPROVAL: '等待审批',
        WAITING_EXTERNAL: '等待外部任务', BLOCKED: '已阻止', SUCCEEDED: '已完成', FAILED: '失败',
        COMPLETED: '已处理', DENIED: '拒绝', CANCELLED: '已取消', PRODUCED: '已生成',
        NOT_PRODUCED: '尚未生成', SKIPPED: '已跳过', UNAVAILABLE: '无记录', REDACTED: '已隐藏',
        PENDING: '等待生成', CONTINUATION: '继续任务', NEW_TASK: '新任务', MODIFICATION: '修改任务'
    };
    let credential = '';
    let generation = 0;
    let conversation;
    let selected;
    let reply;
    let retry;
    let busy = false;
    let traceEpoch = 0;
    let poll;

    const state = status => labels[status] || status || '待返回';
    const errorText = error => error.name === 'TimeoutError' ? '请求超时，可以使用原消息重试。'
        : error instanceof TypeError ? '无法连接应用，请确认服务正在运行。' : error.message;
    const isWaiting = outcome => ['NEEDS_CLARIFICATION', 'WAITING_CONFIRMATION'].includes(outcome.status);
    const selectedTurn = () => conversation?.turns.find(turn => turn.receipt.turnId === selected);
    const turnPath = (session, id) => '/v1/conversations/' + encodeURIComponent(session.id) + '/turns'
        + (id ? '/' + encodeURIComponent(id) : '');
    const status = text => { byId('chat-status').textContent = text; };

    async function request(path, { method = 'GET', body, key } = {}) {
        const headers = { Authorization: 'Bearer ' + credential, Accept: 'application/json' };
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (key) headers['Idempotency-Key'] = key;
        const response = await fetch(path, { method, headers, cache: 'no-store', signal: AbortSignal.timeout(15_000),
            body: body === undefined ? undefined : JSON.stringify(body) });
        const payload = await response.json().catch(() => null);
        if (response.ok && payload) return payload;
        const explanation = {
            401: '会话凭证无效或已过期。', 403: '当前凭证缺少会话权限。',
            404: '找不到资源，请检查会话归属和 intent Profile。'
        };
        throw new Error(payload?.message || explanation[response.status] || '请求失败（' + response.status + '）');
    }

    function setControls() {
        byId('chat-new').disabled = !credential || busy;
        byId('chat-input').disabled = !credential || busy;
        byId('chat-send').disabled = !credential || busy;
        byId('chat-conversation').disabled = !credential || busy || !conversation;
        byId('chat-refresh').disabled = !selected;
        byId('chat-reply').hidden = !reply;
        byId('chat-reply-label').textContent = reply ? '回复第 ' + reply.number + ' 轮的 ' + reply.intent + ' 任务' : '';
    }

    function stamp(title, value) {
        const row = element('div', 'chat-stamp');
        row.append(element('dt', '', title), element('dd', '', value ?? '—'));
        return row;
    }

    function badge(value) {
        const ok = ['READY', 'SUCCEEDED', 'COMPLETED', 'PRODUCED'].includes(value);
        const bad = ['BLOCKED', 'DENIED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(value);
        const el = element('span', 'badge ' + (ok ? 'active' : bad ? 'high' : 'draft'), state(value));
        el.title = value || '';
        return el;
    }

    function toggleReply(turn, outcome) {
        reply = { id: turn.receipt.assistantMessageId, taskId: outcome.taskId, number: turn.number, intent: outcome.intent };
        setControls();
        byId('chat-input').focus();
    }

    function renderThread(scroll = false) {
        const host = byId('chat-thread');
        const scrollTop = host.scrollTop;
        host.replaceChildren();
        if (!conversation?.turns.length) {
            const empty = element('div', 'chat-empty');
            empty.append(element('strong', '', '开始一轮对话'), element('p', 'hint',
                credential ? '发送消息后可在右侧逐层查看产物。' : '连接会话凭证后，即可发送消息。'));
            if (credential) {
                empty.append(button('试试查订单', () => setPrompt('查一下订单')),
                    button('试试销售分析', () => setPrompt('分析上季度销售下降的原因')));
            }
            host.append(empty);
        }
        for (const turn of conversation?.turns || []) {
            const receipt = turn.receipt;
            const card = element('article', 'chat-turn' + (selected === receipt.turnId ? ' selected' : ''));
            const pick = button('第 ' + turn.number + ' 轮 · 查看产物', () => selectTurn(receipt.turnId), 'chat-pick');
            if (selected === receipt.turnId) pick.setAttribute('aria-current', 'true');
            card.append(pick, element('div', 'chat-bubble user', turn.text));
            const assistant = element('div', 'chat-bubble assistant');
            if (receipt.reply) assistant.append(element('p', '', receipt.reply));
            for (const outcome of receipt.outcomes || []) {
                const line = element('div', 'chat-outcome');
                line.append(element('code', '', outcome.intent), badge(outcome.status));
                if (outcome.taskId && isWaiting(outcome)) {
                    line.append(button('回复此任务', () => toggleReply(turn, outcome)));
                }
                assistant.append(line);
            }
            for (const execution of receipt.executions || []) {
                const line = element('div', 'chat-outcome');
                line.append(element('span', '', execution.mode), badge(execution.status));
                assistant.append(line);
                if (execution.output != null) assistant.append(element('pre', 'chat-json', format(execution.output)));
            }
            card.append(assistant);
            host.append(card);
        }
        host.scrollTop = scroll ? host.scrollHeight : scrollTop;
    }

    function setPrompt(text) {
        byId('chat-input').value = text;
        byId('chat-input').focus();
    }

    function renderConversations() {
        const list = byId('chat-conversation');
        list.replaceChildren();
        if (!conversations.size) list.append(element('option', '', '尚未创建'));
        for (const session of conversations.values()) {
            const option = element('option', '', '会话 ' + session.number + ' · ' + session.id.slice(0, 8));
            option.value = session.id;
            list.append(option);
        }
        if (conversation) list.value = conversation.id;
        setControls();
    }

    function stageView(stage) {
        const details = element('details', 'chat-stage');
        const summary = element('summary');
        summary.append(element('span', '', stage.artifact), badge(stage.status));
        details.append(summary);
        if (stage.data != null) details.append(element('pre', 'chat-json', format(stage.data)));
        return details;
    }

    function renderInspector(turn, trace = null, feedback = '', result = null) {
        const host = byId('chat-inspector');
        const stageState = new Map([...host.querySelectorAll('details[data-stage]')].map(el => [el.dataset.stage, el.open]));
        host.replaceChildren();
        if (!turn) {
            host.append(element('p', 'hint chat-inspector-empty', '选择一个对话轮次，查看它关联的 Task、Run 和中间产物。'));
            return;
        }
        const lineage = element('dl', 'chat-lineage');
        lineage.append(stamp('Conversation · 会话', conversation.id),
            stamp('Turn · 第 ' + turn.number + ' 轮', turn.receipt.turnId));
        host.append(lineage);
        if (feedback) host.append(element('p', 'hint chat-feedback', feedback));
        if (trace?.status === 'REDACTED') return;
        const addStages = (parent, stages, group) => {
            for (const [index, stage] of (stages || []).entries()) {
                const block = stageView(stage);
                block.dataset.stage = turn.receipt.turnId + '/' + group + '/' + index;
                block.open = stageState.get(block.dataset.stage) ?? ['SlotState', 'ValidatedIntentIR'].includes(stage.artifact);
                parent.append(block);
            }
        };
        addStages(host, trace?.intake, 'intake');
        for (const [ordinal, outcome] of (turn.receipt.outcomes || []).entries()) {
            const stages = trace?.candidates?.find(item => item.ordinal === ordinal)?.stages || [];
            const candidate = element('section', 'chat-candidate');
            const heading = element('div', 'chat-candidate-title');
            heading.append(element('h3', '', outcome.intent), badge(outcome.status));
            candidate.append(heading);
            const task = element('dl', 'chat-lineage');
            task.append(stamp('Task · 业务任务', outcome.taskId), stamp('关联方式', state(outcome.relation)));
            if (outcome.ir) task.append(stamp('任务输入版本', outcome.ir.inputVersion), stamp('意图定义版本', outcome.ir.definitionVersion));
            const execution = turn.receipt.executions?.find(item => item.candidateOrdinal === ordinal);
            if (execution) {
                task.append(stamp(execution.mode === 'DIRECT' ? 'Tool Execution · 工具执行' : 'Run · 执行实例',
                    execution.targetId), stamp('执行状态', state(execution.status)));
                if (execution.reason) task.append(stamp('原因', execution.reason));
                const run = stages.find(stage => stage.artifact === 'StateUpdate')?.data?.run;
                if (run) {
                    task.append(stamp('计划版本', run.planVersion), stamp('Ralph 验收轮次', run.iteration),
                        stamp('Run 事件序号', run.eventSeq));
                }
            }
            candidate.append(task);
            const related = conversation.turns.filter(other => other.receipt.outcomes?.some(item => outcome.taskId && item.taskId === outcome.taskId));
            if (related.length > 1) {
                const timeline = element('div', 'chat-task-turns');
                timeline.append(element('span', 'hint', '同一 Task 的轮次'));
                for (const prior of related) timeline.append(button('第 ' + prior.number + ' 轮', () => selectTurn(prior.receipt.turnId)));
                candidate.append(timeline);
            }
            if (execution?.mode !== 'DIRECT' && /^\/v1\/runs\/[0-9a-f-]+\/result$/i.test(execution?.resultUrl || '')) {
                candidate.append(button('查看运行结果', () => loadResult(turn, execution.resultUrl)));
                if (result?.url === execution.resultUrl) candidate.append(element('pre', 'chat-json', format(result.data)));
            }
            addStages(candidate, stages, ordinal);
            host.append(candidate);
        }
        if (trace?.response) addStages(host, [trace.response], 'response');
    }

    async function loadResult(turn, url) {
        const epoch = traceEpoch;
        try {
            const data = await request(url);
            if (epoch === traceEpoch) {
                turn.result = { url, data };
                renderInspector(turn, turn.trace, '', turn.result);
            }
        } catch (error) {
            if (epoch === traceEpoch) renderInspector(turn, turn.trace, errorText(error), turn.result);
        }
    }

    function stopTrace() {
        clearTimeout(poll);
        traceEpoch++;
    }

    async function refresh() {
        stopTrace();
        const epoch = traceEpoch;
        const turn = selectedTurn();
        if (!turn || byId('chat-page').hidden) return;
        const path = turnPath(conversation, turn.receipt.turnId);
        try {
            const trace = await request(path + '/artifacts');
            if (epoch !== traceEpoch) return;
            turn.trace = trace;
            if (trace.status === 'REDACTED') {
                renderInspector(turn, trace, '该轮产物已隐藏。');
                return;
            }
            const executions = trace.response?.data?.executions;
            if (executions && format(executions) !== format(turn.receipt.executions || [])) {
                turn.receipt.executions = executions;
                renderThread();
            }
            renderInspector(turn, trace, '', turn.result);
            if (turn.receipt.executions?.some(run => !terminal.has(run.status))) poll = setTimeout(refresh, 2150);
        } catch (error) {
            if (epoch === traceEpoch) renderInspector(turn, null, errorText(error));
        }
    }

    function selectTurn(id) {
        selected = id;
        const turn = selectedTurn();
        renderThread();
        renderInspector(turn, turn?.trace, turn?.trace ? '' : '正在读取中间产物…', turn?.result);
        setControls();
        refresh();
    }

    async function createConversation() {
        const receipt = await request('/v1/conversations', { method: 'POST' });
        const created = { id: receipt.conversationId, number: conversations.size + 1, turns: [] };
        return created;
    }

    function activate(session) {
        conversation = session;
        retry = null;
        reply = null;
        byId('chat-input').value = '';
        renderConversations();
        selectTurn(session?.turns.at(-1)?.receipt.turnId);
    }

    byId('chat-form').addEventListener('submit', async event => {
        event.preventDefault();
        const text = byId('chat-input').value.trim();
        if (!text || !credential || busy) return;
        const connection = generation;
        busy = true;
        setControls();
        status('正在提交…');
        try {
            if (!conversation) {
                const created = await createConversation();
                if (connection !== generation) return;
                conversations.set(created.id, created);
                conversation = created;
                renderConversations();
            }
            const body = reply ? { text, targetTaskId: reply.taskId, replyToMessageId: reply.id } : { text };
            const fingerprint = format(body);
            if (retry?.fingerprint !== fingerprint) {
                const key = crypto.randomUUID?.()
                    ?? Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
                retry = { fingerprint, key };
            }
            const receipt = await request(turnPath(conversation), { method: 'POST', body, key: retry.key });
            if (connection !== generation) return;
            const turn = { receipt, text, number: conversation.turns.length + 1 };
            conversation.turns.push(turn);
            reply = null;
            retry = null;
            byId('chat-input').value = '';
            selectTurn(receipt.turnId);
            const pending = receipt.outcomes?.filter(item => item.taskId && isWaiting(item));
            if (pending?.length === 1) toggleReply(turn, pending[0]);
            renderThread(true);
            status('');
        } catch (error) {
            if (connection === generation) status(errorText(error));
        } finally {
            if (connection === generation) {
                busy = false;
                setControls();
                byId('chat-input').focus();
            }
        }
    });

    byId('chat-new').addEventListener('click', async () => {
        const connection = generation;
        busy = true;
        setControls();
        status('正在创建会话…');
        try {
            const created = await createConversation();
            if (connection !== generation) return;
            conversations.set(created.id, created);
            activate(created);
            status('');
        } catch (error) {
            if (connection === generation) status(errorText(error));
        } finally {
            if (connection === generation) { busy = false; setControls(); }
        }
    });
    byId('chat-conversation').addEventListener('change', event => activate(conversations.get(event.target.value)));
    byId('chat-clear-reply').addEventListener('click', () => { reply = null; setControls(); byId('chat-input').focus(); });
    byId('chat-refresh').addEventListener('click', refresh);
    byId('chat-connect').addEventListener('click', () => byId('chat-auth').showModal());
    byId('chat-auth').addEventListener('close', () => { byId('chat-token').value = ''; });
    function connect(value) {
        if (credential !== value) {
            generation++;
            credential = value;
            busy = false;
            conversations.clear();
            activate(null);
            status('');
        }
        byId('chat-connect').textContent = value ? '会话凭证已设置' : '连接会话凭证';
        byId('chat-disconnect').hidden = !value;
        byId('chat-auth').close();
    }
    byId('chat-auth-form').addEventListener('submit', event => {
        event.preventDefault();
        const value = byId('chat-token').value.trim().replace(/^Bearer\s+/i, '');
        if (value) connect(value);
    });
    byId('chat-disconnect').addEventListener('click', () => connect(''));
    document.addEventListener('admin-page', event => event.detail === 'chat' ? refresh() : stopTrace());
    renderThread();
    renderInspector(null);
})();
