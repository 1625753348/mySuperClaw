const byId = id => document.getElementById(id);
const fields = id => byId(id).elements;
const root = new URL('../v1/admin/tools', location.href).href;
const statusLabels = { DRAFT: '草稿', ACTIVE: '已发布', DISABLED: '已停用', PUBLISHED: '已发布', DEPRECATED: '已弃用' };
const riskLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '极高' };
const protocolLabels = { HTTP: 'HTTP', MCP: 'MCP', LOCAL: '自研工具', GRPC: 'gRPC',
    DATABASE: '数据库', ASYNC: '异步', UNBOUND: '待绑定' };
const targetHints = {
    HTTP: '选择部署配置中 enterprise.gateway.targets 下的 HTTP 别名。',
    MCP: '选择 enterprise.gateway.mcp-tools 下已配置的远端工具绑定。',
    LOCAL: '选择已部署的 LocalTool Bean 返回的 name()。',
    GRPC: '选择部署中已装配的 GrpcToolBinding 别名。',
    DATABASE: '选择已部署的只读 DatabaseToolBinding 模板。',
    ASYNC: '选择部署中已配置的异步提交与回查端点。'
};
const policyExamples = {
    ABAC: { roles: ['operations'], values: { '/region': ['CN'] } },
    QUOTA: { scope: 'PRINCIPAL', perMinute: 10, perDay: 100 },
    APPROVAL: { ttlSeconds: 900 },
    RETRY: { maxAttempts: 1, failureThreshold: 5, openSeconds: 30 },
    REDACTION: { remove: ['/internalId'] }
};
const format = value => JSON.stringify(value, null, 2);
const editor = byId('editor');
const auth = byId('auth');
let token = '';
let protocol = '';
let filter = { query: '', status: '' };
let cursors = [''];
let catalogRun = 0;
let editorRun = 0;
let optionsRun = 0;
let mcpRun = 0;
let detail;
let targetOptions;
let mcpCursor;

function element(tag, className, text) {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (text != null) el.textContent = text;
    return el;
}

function button(text, action, style = '') {
    const el = element('button', 'button ' + style, text);
    el.type = 'button';
    el.addEventListener('click', () => action(el));
    return el;
}

function message(id, text = '') {
    byId(id).textContent = text;
    byId(id).hidden = !text;
}

async function api(path = '', { method = 'GET', body, credential = token } = {}) {
    const headers = { Accept: 'application/json' };
    if (credential) headers.Authorization = 'Bearer ' + credential;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(root + path, {
        method, headers, cache: 'no-store', signal: AbortSignal.timeout(15_000),
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (response.status === 204) return null;
    const payload = await response.json().catch(() => null);
    if (response.ok) return payload;
    const defaults = {
        401: '凭证无效或已过期，请重新连接。',
        403: '当前凭证缺少 tools.admin 权限或租户信息。',
        404: '未找到资源，请确认网关已启用。',
        409: '数据已变更，请刷新后重试。'
    };
    throw Object.assign(new Error(payload?.message ?? defaults[response.status] ?? '请求失败（' + response.status + '）'), {
        status: response.status
    });
}

function failure(error) {
    if (error.name === 'TimeoutError') return '网关请求超时，请重试。';
    if (error instanceof TypeError) return '无法连接网关，请检查应用是否运行。';
    return error.message;
}

function openAuth() {
    message('auth-error');
    byId('token').value = '';
    byId('disconnect').hidden = !token;
    auth.showModal();
    byId('token').focus();
}

function setConnection(value) {
    token = value;
    byId('connection-dot').classList.toggle('connected', !!value);
    byId('connection-label').textContent = value ? '管理凭证已连接' : '连接管理凭证';
    message('notice');
    cursors = [''];
    loadTools();
}

byId('credentials').addEventListener('click', openAuth);
byId('catalog-connect').addEventListener('click', () => token ? loadTools() : openAuth());
byId('disconnect').addEventListener('click', () => {
    setConnection('');
    auth.close();
});
auth.addEventListener('close', () => { byId('token').value = ''; });
byId('auth-form').addEventListener('submit', async event => {
    event.preventDefault();
    const submit = event.submitter;
    const candidate = byId('token').value.trim().replace(/^Bearer\s+/i, '');
    submit.disabled = true;
    message('auth-error');
    try {
        await api('/options/targets?protocol=HTTP', { credential: candidate });
        if (auth.open) {
            setConnection(candidate);
            auth.close();
        }
    } catch (error) {
        message('auth-error', failure(error));
    } finally {
        submit.disabled = false;
    }
});

document.querySelectorAll('[data-close]').forEach(el => el.addEventListener('click', () => byId(el.dataset.close).close()));

function showPage(rules) {
    byId('rules-page').hidden = !rules;
    byId('catalog-page').hidden = rules;
    byId('breadcrumb').textContent = rules ? '预定义规则' : '工具目录';
    byId(rules ? 'rules-nav' : 'catalog-nav').setAttribute('aria-current', 'page');
    byId(rules ? 'catalog-nav' : 'rules-nav').removeAttribute('aria-current');
    message('notice');
}

byId('catalog-nav').addEventListener('click', () => showPage(false));
byId('rules-nav').addEventListener('click', () => showPage(true));

function catalogState(title, copy, action = '') {
    byId('catalog-state').hidden = !title;
    byId('catalog-state-title').textContent = title;
    byId('catalog-state-copy').textContent = copy;
    byId('catalog-connect').hidden = !action;
    byId('catalog-connect').textContent = action;
}

async function loadTools() {
    const run = ++catalogRun;
    byId('tools').replaceChildren();
    byId('previous-page').disabled = true;
    byId('next-page').disabled = true;
    byId('page-info').textContent = '工具目录按名称排序';
    if (!token) {
        catalogState('连接后查看工具目录', '使用具有 tools.admin 权限的 JWT 管理当前租户的工具。', '连接凭证');
        return;
    }
    catalogState('正在读取工具', '正在查询当前租户的工具目录。');
    const query = new URLSearchParams({ ...filter, protocol, after: cursors.at(-1), limit: 20 });
    try {
        const page = await api('?' + query);
        if (run !== catalogRun) return;
        for (const tool of page.items) renderTool(tool);
        catalogState(page.items.length ? '' : '没有匹配的工具',
            filter.query || filter.status ? '可以修改搜索条件或切换状态。' : '可以切换协议标签或注册第一个工具。');
        byId('page-info').textContent = '第 ' + cursors.length + ' 页 · 本页 ' + page.items.length + ' 项';
        byId('previous-page').disabled = cursors.length === 1;
        byId('next-page').disabled = !page.nextAfter;
        byId('next-page').onclick = () => {
            cursors.push(page.nextAfter);
            loadTools();
        };
    } catch (error) {
        if (run !== catalogRun) return;
        if (error.status === 401) {
            setConnection('');
            catalogState('管理凭证已过期', failure(error), '重新连接');
        } else {
            catalogState('读取工具失败', failure(error), '重试');
        }
    }
}

function statusBadge(status) {
    return element('span', 'badge ' + status.toLowerCase(), statusLabels[status] ?? status);
}

function renderTool(tool) {
    const row = element('tr');
    const first = element('td');
    first.append(element('span', 'tool-name', tool.name), element('span', 'tool-desc', tool.description));
    if (tool.category) first.append(element('span', 'tool-desc', tool.category));
    const protocolCell = element('td');
    const badges = element('p', 'badges');
    (tool.protocols || 'UNBOUND').split(',').forEach(value => badges.append(element('span', 'badge', protocolLabels[value] ?? value)));
    protocolCell.append(badges);
    const state = element('td');
    state.append(statusBadge(tool.status));
    const version = element('td');
    version.append(element('code', '', tool.currentVersion ?? '—'));
    const risk = element('td');
    risk.append(element('span', 'badge ' + (['HIGH', 'CRITICAL'].includes(tool.riskLevel) ? 'high' : ''), riskLabels[tool.riskLevel] ?? tool.riskLevel));
    const controls = element('td');
    const manage = button('管理', () => openDetail(tool.name));
    manage.setAttribute('aria-label', '管理 ' + tool.name);
    controls.append(manage);
    row.append(first, protocolCell, state, version, risk, controls);
    byId('tools').append(row);
}

document.querySelectorAll('[data-protocol]').forEach(tab => tab.addEventListener('click', () => {
    document.querySelector('[data-protocol][aria-pressed="true"]').setAttribute('aria-pressed', 'false');
    tab.setAttribute('aria-pressed', 'true');
    protocol = tab.dataset.protocol;
    cursors = [''];
    loadTools();
}));
byId('filters').addEventListener('submit', event => {
    event.preventDefault();
    filter = { query: byId('keyword').value.trim(), status: byId('status-filter').value };
    cursors = [''];
    loadTools();
});
byId('status-filter').addEventListener('change', () => byId('filters').requestSubmit());
byId('previous-page').addEventListener('click', () => {
    cursors.pop();
    loadTools();
});

const toolPath = name => '/' + encodeURIComponent(name);

function showEditor(title, subtitle, editing) {
    byId('editor-title').textContent = title;
    byId('editor-subtitle').textContent = subtitle;
    byId('tool-detail').hidden = true;
    byId('editor-form').hidden = !editing;
    message('editor-error');
    if (!editor.open) editor.showModal();
    byId('editor-error').parentElement.scrollTop = 0;
}

async function openDetail(name) {
    const run = ++editorRun;
    detail = null;
    showEditor(name, '正在读取工具与版本。', false);
    try {
        const result = await api(toolPath(name));
        if (run !== editorRun || !editor.open) return;
        detail = result;
        renderDetail();
    } catch (error) {
        if (run === editorRun) message('editor-error', failure(error));
    }
}

function renderDetail() {
    const { tool, versions } = detail;
    showEditor(tool.name, '管理版本、发布状态和工具授权。', false);
    byId('tool-detail').hidden = false;
    const summary = byId('detail-summary');
    const metadata = element('div', 'badges');
    metadata.append(statusBadge(tool.status), element('span', 'badge', '风险 ' + (riskLabels[tool.riskLevel] ?? tool.riskLevel)));
    if (tool.category) metadata.append(element('span', 'badge', tool.category));
    summary.replaceChildren(element('p', '', tool.description), metadata);
    byId('disable-tool').hidden = tool.status !== 'ACTIVE';
    byId('policy-box').open = false;
    const list = byId('version-list');
    list.replaceChildren();
    if (!versions.length) list.append(element('p', 'hint', '尚未绑定版本。新增版本时选择协议和部署端点。'));
    for (const version of versions) {
        const current = version.id === tool.currentVersionId;
        const card = element('article', 'version-card');
        const top = element('div', 'version-top');
        top.append(element('strong', '', version.versionName), statusBadge(version.status));
        if (current) top.append(element('span', 'badge', '当前版本'));
        if (version.protocol) top.append(element('span', 'badge', protocolLabels[version.protocol] ?? version.protocol));
        const body = element('p', 'hint', version.targetRef ? version.targetRef + ' · ' + version.timeoutMs + ' ms' : '未绑定端点');
        const controls = element('div', 'actions');
        const schema = element('pre', 'schema-view');
        schema.hidden = true;
        const schemaButton = button('查看 Schema', async control => {
            if (!schema.hidden) {
                schema.hidden = true;
                control.textContent = '查看 Schema';
                return;
            }
            await withButton(control, async () => {
                const contract = await api(toolPath(tool.name) + '/versions/' + encodeURIComponent(version.versionName));
                schema.textContent = format({ inputSchema: contract.inputSchema, outputSchema: contract.outputSchema });
                schema.hidden = false;
                control.textContent = '收起 Schema';
            });
        });
        controls.append(schemaButton);
        if (version.targetRef && ['DRAFT', 'PUBLISHED'].includes(version.status) && !(current && tool.status === 'ACTIVE')) {
            controls.append(button(version.status === 'DRAFT' || tool.status !== 'ACTIVE' ? '发布此版本' : '设为当前版本',
                control => changePublication(control, 'publish', version.versionName), 'primary'));
        }
        card.append(top, body, controls, schema);
        list.append(card);
    }
}

async function withButton(control, task) {
    const run = editorRun;
    if (control) control.disabled = true;
    message('editor-error');
    try {
        await task();
    } catch (error) {
        if (run === editorRun) message('editor-error', failure(error));
    } finally {
        if (control) control.disabled = false;
    }
}

async function changePublication(control, action, version) {
    const { tool } = detail;
    const publish = action === 'publish';
    if (!confirm(publish ? '将 ' + tool.name + ' 的 ' + version + ' 设为当前发布版本？' : '停用 ' + tool.name + '？')) return;
    const run = editorRun;
    await withButton(control, async () => {
        try {
            await api(toolPath(tool.name) + '/' + action, {
                method: 'POST', body: { version, expectedRevision: tool.revision }
            });
        } catch (error) {
            if (error.status === 409 && run === editorRun) {
                await openDetail(tool.name);
                message('editor-error', failure(error));
                loadTools();
                return;
            }
            throw error;
        }
        if (run === editorRun) await openDetail(tool.name);
        loadTools();
        message('notice', publish ? '版本已发布。' : '工具已停用。');
    });
}

byId('disable-tool').addEventListener('click', event => changePublication(event.currentTarget, 'disable'));
byId('permissions').addEventListener('submit', event => {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget));
    values.subjectId = values.subjectId.trim();
    withButton(event.submitter, async () => {
        await api(toolPath(detail.tool.name) + '/permissions', { method: 'PUT', body: values });
        event.submitter.textContent = '授权已保存';
    });
});
byId('permissions').addEventListener('input', () => { byId('permissions').querySelector('button').textContent = '保存授权'; });

const policyFields = fields('policy-form');
let policyVersions = null;

function showPolicy() {
    const kind = policyFields.kind.value;
    const policy = policyVersions?.find(item => item.kind === kind);
    policyFields.rule.value = format(policy?.rule ?? policyExamples[kind]);
    byId('policy-state').textContent = policy ? '生效版本 ' + policy.version : policyVersions ? '尚未启用' : '正在读取策略';
    byId('disable-policy').disabled = !policy;
    byId('policy-form').querySelector('[type=submit]').disabled = !policyVersions;
}

byId('policy-box').addEventListener('toggle', async event => {
    if (!event.target.open || !detail) return;
    const tool = detail.tool;
    policyVersions = null;
    showPolicy();
    await withButton(null, async () => {
        const loaded = await api(toolPath(tool.name) + '/policies');
        if (detail?.tool === tool) {
            policyVersions = loaded;
            showPolicy();
        }
    });
});
policyFields.kind.addEventListener('change', showPolicy);

async function savePolicy(control, disable) {
    const tool = detail.tool;
    const kind = policyFields.kind.value;
    const expectedVersion = policyVersions.find(item => item.kind === kind)?.version ?? 0;
    let updated;
    await withButton(control, async () => {
        const rule = disable ? null : schemaField(policyFields.rule.value, '策略 JSON ');
        updated = await api(toolPath(tool.name) + '/policies/' + kind, {
            method: 'PUT', body: { expectedVersion, rule }
        });
    });
    if (updated && detail?.tool === tool) {
        policyVersions = updated;
        showPolicy();
    }
}
byId('policy-form').addEventListener('submit', event => {
    event.preventDefault();
    if (policyVersions) savePolicy(event.submitter, false);
});
byId('disable-policy').addEventListener('click', event => {
    if (policyVersions && confirm('停用选中的执行策略？')) savePolicy(event.currentTarget, true);
});

const values = fields('editor-form');
const emptySchema = { type: 'object', properties: {}, additionalProperties: false };

function openForm(newTool) {
    byId('editor-form').reset();
    byId('tool-fields').hidden = !newTool;
    byId('tool-fields').disabled = !newTool;
    values.version.value = newTool ? '1.0.0' : '';
    values.protocol.value = protocolLabels[protocol] && protocol !== 'UNBOUND' ? protocol : 'HTTP';
    values.timeoutMs.value = 5000;
    values.inputSchema.value = format(emptySchema);
    showEditor(newTool ? '注册工具' : '新增版本', newTool ? '保存工具定义和首个版本草稿。' : detail.tool.name, true);
    loadOptions();
}

byId('new-tool').addEventListener('click', () => {
    if (!token) {
        openAuth();
        return;
    }
    ++editorRun;
    detail = null;
    openForm(true);
});
byId('add-version').addEventListener('click', () => openForm(false));
byId('cancel-edit').addEventListener('click', () => detail ? renderDetail() : editor.close());
editor.addEventListener('close', () => { ++editorRun; ++optionsRun; ++mcpRun; });

function populate(select, choices, blank) {
    select.replaceChildren(new Option(blank, ''), ...choices.map(item => new Option(item, item)));
}

function resetMcp() {
    ++mcpRun;
    mcpCursor = null;
    byId('mcp-tools').replaceChildren();
    byId('mcp-next').hidden = true;
    byId('mcp-note').textContent = '选中远端工具可带入描述和 Schema。';
}

async function loadOptions() {
    const selected = values.protocol.value;
    const run = ++optionsRun;
    resetMcp();
    targetOptions = null;
    byId('mcp-import').hidden = selected !== 'MCP';
    byId('target-hint').textContent = targetHints[selected];
    byId('mcp-list').disabled = true;
    byId('save-draft').disabled = true;
    populate(values.targetRef, [], '正在读取部署端点…');
    populate(values.credentialRef, [], '不使用凭证引用');
    populate(byId('mcp-server'), [], '选择受管服务');
    try {
        const result = await api('/options/targets?protocol=' + selected);
        if (run !== optionsRun || !editor.open) return;
        targetOptions = result;
        populate(values.targetRef, result.aliases, result.aliases.length ? '选择端点别名' : '该协议暂无部署端点');
        if (result.aliases.length === 1) values.targetRef.value = result.aliases[0];
        populate(values.credentialRef, result.credentialRefs ?? [], '不使用凭证引用');
        populate(byId('mcp-server'), result.servers, '选择受管服务');
        if (result.servers.length === 1) byId('mcp-server').value = result.servers[0];
        byId('mcp-list').disabled = !byId('mcp-server').value;
        byId('save-draft').disabled = !result.aliases.length;
    } catch (error) {
        if (run === optionsRun) message('editor-error', failure(error));
    }
}

values.protocol.addEventListener('change', () => {
    message('editor-error');
    loadOptions();
});
byId('mcp-server').addEventListener('change', event => {
    resetMcp();
    byId('mcp-list').disabled = !event.target.value;
});

async function loadMcp(append) {
    const server = byId('mcp-server').value;
    if (!append) resetMcp();
    const run = ++mcpRun;
    const query = mcpCursor ? '?' + new URLSearchParams({ cursor: mcpCursor }) : '';
    byId('mcp-list').disabled = true;
    byId('mcp-next').disabled = true;
    try {
        const page = await api('/mcp/' + encodeURIComponent(server) + '/catalog' + query);
        if (run !== mcpRun || !editor.open) return;
        for (const item of page.tools) {
            byId('mcp-tools').append(button(item.name, () => {
                if (!detail) {
                    values.name.value = item.name.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 64);
                    values.description.value = (item.description || item.name).slice(0, 2048);
                }
                values.inputSchema.value = format(item.inputSchema ?? emptySchema);
                values.outputSchema.value = item.outputSchema ? format(item.outputSchema) : '';
                const binding = targetOptions.bindings.find(entry => entry.server === server && entry.toolName === item.name);
                values.targetRef.value = binding?.alias ?? '';
                byId('mcp-note').textContent = binding ? '已带入 ' + item.name + ' 的 Schema。' : '已带入 Schema。该远端工具还需要在部署配置中绑定别名。';
            }));
        }
        if (!page.tools.length && !append) byId('mcp-note').textContent = '该服务未返回工具。';
        mcpCursor = page.nextCursor;
        byId('mcp-next').hidden = !mcpCursor;
    } catch (error) {
        if (run === mcpRun) message('editor-error', failure(error));
    } finally {
        if (run === mcpRun) {
            byId('mcp-list').disabled = false;
            byId('mcp-next').disabled = false;
        }
    }
}

byId('mcp-list').addEventListener('click', () => loadMcp(false));
byId('mcp-next').addEventListener('click', () => loadMcp(true));

function schemaField(raw, label) {
    try {
        const schema = JSON.parse(raw);
        if (!schema || typeof schema !== 'object' || Array.isArray(schema)) throw new Error();
        return schema;
    } catch {
        throw new Error(label + '必须是有效的 JSON 对象。');
    }
}

byId('editor-form').addEventListener('submit', event => {
    event.preventDefault();
    const existing = detail?.tool;
    const run = editorRun;
    withButton(event.submitter, async () => {
        const version = {
            version: values.version.value.trim(),
            protocol: values.protocol.value,
            targetRef: values.targetRef.value,
            credentialRef: values.credentialRef.value || null,
            idempotencySupported: values.idempotencySupported.checked,
            timeoutMs: Number(values.timeoutMs.value),
            inputSchema: schemaField(values.inputSchema.value, '输入 Schema '),
            outputSchema: values.outputSchema.value.trim() ? schemaField(values.outputSchema.value, '输出 Schema ') : null
        };
        const tool = existing ?? {
            name: values.name.value.trim(),
            description: values.description.value.trim(),
            category: values.category.value.trim() || null,
            riskLevel: values.riskLevel.value
        };
        await api(existing ? toolPath(tool.name) + '/versions' : '/drafts', {
            method: 'POST', body: existing ? version : { tool, version }
        });
        if (run === editorRun) await openDetail(tool.name);
        loadTools();
        message('notice', '版本草稿已保存。');
    });
});

const rule = fields('rule-form');
rule.parameters.value = format(emptySchema);

function ruleDraft() {
    return {
        domain: rule.domain.value.trim(),
        action: rule.action.value.trim(),
        parameters: schemaField(rule.parameters.value, '参数定义 '),
        clarification: rule.clarification.value.trim(),
        safetyBoundary: {
            allowedRoles: rule.roles.value.split(/[,，]/).map(role => role.trim()).filter(Boolean),
            dataScope: rule.dataScope.value.trim(),
            approval: rule.approval.value,
            effect: rule.effect.value,
            constraints: rule.constraints.value.trim()
        }
    };
}

function previewRule() {
    try {
        byId('rule-preview').textContent = format(ruleDraft());
        message('rule-error');
    } catch (error) {
        message('rule-error', error.message);
    }
}

byId('rule-form').addEventListener('input', previewRule);
byId('rule-example').addEventListener('click', () => {
    rule.domain.value = 'ORDER';
    rule.action.value = 'LOOKUP';
    rule.parameters.value = format({
        type: 'object',
        properties: { orderId: { type: 'string', description: '订单号', minLength: 1 } },
        required: ['orderId'],
        additionalProperties: false
    });
    rule.clarification.value = '请提供要查询的订单号。';
    rule.roles.value = 'operations';
    rule.dataScope.value = '当前租户内有权限查看的订单';
    rule.approval.value = 'NONE';
    rule.effect.value = 'READ_ONLY';
    rule.constraints.value = '每次查询一个订单，仅返回订单状态。';
    previewRule();
});
byId('rule-form').addEventListener('submit', event => {
    event.preventDefault();
    try {
        const draft = ruleDraft();
        if (!draft.domain || !draft.action) throw new Error('请填写业务领域和执行动作。');
        const href = URL.createObjectURL(new Blob([format(draft) + '\n'], { type: 'application/json' }));
        const link = element('a');
        link.href = href;
        link.download = (draft.domain + '-' + draft.action).replace(/[^A-Za-z0-9\p{L}_-]/gu, '_') + '.json';
        link.click();
        setTimeout(() => URL.revokeObjectURL(href), 1000);
        message('rule-error');
    } catch (error) {
        message('rule-error', error.message);
    }
});
previewRule();
