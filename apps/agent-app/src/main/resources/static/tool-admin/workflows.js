(() => {
    'use strict';

    const segment = value => encodeURIComponent(value);
    const refreshButton = (id, work) => byId(id).addEventListener('click', work);
    const runRoot = new URL('../v1/runs', location.href).href;

    function renderList(host, items, display, empty, append = false) {
        if (!append) host.replaceChildren();
        if (!items.length) {
            if (!append) host.append(element('p', 'hint', empty));
            return;
        }
        const list = host.lastElementChild ?? element('ul', 'management-list');
        for (const item of items) {
            const row = element('li');
            row.append(...display(item));
            list.append(row);
        }
        if (!list.parentNode) host.append(list);
    }

    function record(text) {
        return element('span', 'record-line', text.filter(value => value != null && value !== '').join(' · '));
    }

    function json(raw, label) {
        try { return JSON.parse(raw); }
        catch { throw new Error(label + '必须是合法的 JSON。'); }
    }

    const reconcile = byId('ops-reconcile');
    const correction = fields('ops-reconcile-form');
    const confirm = byId('ops-reconcile-form').querySelector('[type="submit"]');
    let check;

    function printStep() {
        const step = check?.steps.find(item => item.stepId === correction.step.value);
        byId('ops-reconcile-execution').textContent = step
            ? [step.tool, step.toolVersion, step.executionId, step.reason].filter(Boolean).join(' · ') : '';
        confirm.disabled = !step?.executionId;
        return step;
    }

    async function reviewRun(run) {
        const current = { run, steps: [] };
        check = current;
        byId('ops-reconcile-form').reset();
        correction.step.replaceChildren(new Option('正在读取步骤…', ''));
        byId('ops-reconcile-run').textContent = 'Run ' + run;
        printStep();
        message('ops-reconcile-error');
        reconcile.showModal();
        try {
            const steps = await api('/reconciliation/' + segment(run) + '/steps', { base: opsRoot });
            if (check !== current || !reconcile.open) return;
            current.steps = steps.filter(step => step.executionId);
            correction.step.replaceChildren(...current.steps.map(step => new Option(
                step.node + ' · ' + (step.executionStatus ?? '状态未知'), step.stepId)));
            if (!current.steps.length) correction.step.append(new Option('当前没有可确认的步骤', ''));
            printStep();
        } catch (error) {
            if (check === current && reconcile.open) message('ops-reconcile-error', failure(error));
        }
    }

    document.addEventListener('admin-reconcile', event => reviewRun(event.detail));
    correction.step.addEventListener('change', () => {
        correction.result.value = '';
        correction.evidenceRef.value = '';
        correction.reason.value = '';
        message('ops-reconcile-error');
        printStep();
    });
    reconcile.addEventListener('close', () => {
        check = undefined;
        byId('ops-reconcile-form').reset();
        correction.step.replaceChildren();
        byId('ops-reconcile-execution').textContent = '';
    });
    byId('ops-reconcile-form').addEventListener('submit', async event => {
        event.preventDefault();
        const current = check;
        const step = printStep();
        if (!current || !step?.executionId) return;
        confirm.disabled = true;
        correction.step.disabled = true;
        message('ops-reconcile-error');
        try {
            const result = json(correction.result.value, '外部实际结果 ');
            const evidenceRef = correction.evidenceRef.value.trim();
            const reason = correction.reason.value.trim();
            if (result === null || !evidenceRef || !reason) throw new Error('请填写实际结果、外部凭证引用和核对说明。');
            await api('/reconciliation/' + segment(current.run) + '/steps/' + segment(step.stepId) + '/confirm-success', {
                base: opsRoot, method: 'POST', body: { executionId: step.executionId, result, evidenceRef, reason }
            });
            if (check !== current || !reconcile.open) return;
            reconcile.close();
            message('notice', '外部结果已确认，Worker 将推进原运行。');
            await loadOperations();
        } catch (error) {
            if (check === current && reconcile.open) message('ops-reconcile-error', failure(error));
        } finally {
            correction.step.disabled = false;
            if (check === current && reconcile.open) printStep();
        }
    });

    const monitorFields = fields('monitor-form');
    const monitorHistory = {
        ticks: { host: 'monitor-ticks', more: 'monitor-more-ticks' },
        deliveries: { host: 'monitor-deliveries', more: 'monitor-more-deliveries' }
    };
    const monitorLabels = { ACTIVE: '运行中', PAUSED: '已暂停', EXPIRED: '已到期', ALERT: '告警', RECOVERED: '恢复',
        BREACH: '异常', NORMAL: '正常', NOTIFY: '通知', REMEDIATE: '处置', WAITING_APPROVAL: '等待审批',
        SUCCEEDED: '成功', FAILED: '失败', SKIPPED: '已跳过', PENDING: '待处理', RUNNING: '执行中' };
    const state = value => monitorLabels[value] ?? value;
    let monitorEpoch = 0;
    let selected;
    let monitorDraftId;

    refreshButton('monitor-reset-draft', () => {
        monitorDraftId = undefined;
        byId('monitor-reset-draft').hidden = true;
        message('monitor-status', '下次提交将使用新的监控请求标识。');
    });

    async function loadMonitors() {
        const epoch = ++monitorEpoch;
        const host = byId('monitor-list');
        host.replaceChildren();
        message('monitor-error');
        if (!token) {
            host.append(button('连接监控凭证', openAuth));
            return;
        }
        try {
            const monitors = await api('', { base: monitorRoot });
            if (epoch !== monitorEpoch) return;
            renderList(host, monitors, monitor => [
                record([monitor.tool + '@' + monitor.version, state(monitor.state), monitor.id]),
                button('查看详情', () => selectMonitor(monitor.id))
            ], '暂无监控任务');
        } catch (error) {
            if (epoch === monitorEpoch) message('monitor-error', failure(error));
        }
    }

    function monitorSpec() {
        const map = name => monitorFields[name].value.trim();
        const optional = map('delivery') ? schemaField(map('delivery'), '通知和处置 ') : {};
        if (Object.keys(optional).some(key => !['notifyWith', 'remediateWith', 'remediationSop'].includes(key))) {
            throw new Error('通知和处置只接受 notifyWith、remediateWith 和 remediationSop。');
        }
        const everySeconds = Number(map('everySeconds'));
        const lifetimeSeconds = Number(map('lifetimeSeconds'));
        if (lifetimeSeconds < everySeconds) throw new Error('持续时长不能小于检查间隔。');
        return {
            tool: map('tool'), version: map('version'), field: map('field'), comparison: map('comparison'), action: map('action'),
            threshold: Number(map('threshold')), everySeconds, lifetimeSeconds,
            arguments: schemaField(map('arguments'), '工具参数 '), ...optional
        };
    }

    byId('monitor-form').addEventListener('submit', async event => {
        event.preventDefault();
        if (!token) { openAuth(); return; }
        const credential = token;
        const submit = event.submitter;
        submit.disabled = true;
        byId('monitor-reset-draft').hidden = true;
        message('monitor-error');
        message('monitor-status');
        try {
            const body = monitorSpec();
            monitorDraftId ??= crypto.randomUUID();
            const monitor = await api('/' + monitorDraftId, { base: monitorRoot, method: 'PUT', body });
            if (token !== credential) return;
            monitorDraftId = undefined;
            message('monitor-status', '监控已创建。');
            await Promise.all([loadMonitors(), selectMonitor(monitor.id)]);
        } catch (error) {
            if (token === credential) {
                message('monitor-error', failure(error));
                byId('monitor-reset-draft').hidden = !monitorDraftId;
            }
        } finally {
            submit.disabled = false;
        }
    });

    async function selectMonitor(id) {
        const current = { id, pages: { ticks: 0, deliveries: 0 } };
        selected = current;
        byId('monitor-detail').hidden = true;
        byId('monitor-result').hidden = true;
        byId('monitor-result').textContent = '';
        message('monitor-error');
        try {
            const item = await api('/' + segment(id), { base: monitorRoot });
            if (selected !== current) return;
            current.item = item;
            byId('monitor-summary').textContent = [item.id, item.tool + '@' + item.version, state(item.state), item.reason,
                item.nextAt && '下次 ' + new Date(item.nextAt).toLocaleString(),
                item.expiresAt && '到期 ' + new Date(item.expiresAt).toLocaleString()].filter(Boolean).join(' · ');
            const transition = byId('monitor-transition');
            transition.hidden = !['ACTIVE', 'PAUSED'].includes(item.state);
            transition.textContent = item.state === 'PAUSED' ? '恢复监控' : '暂停监控';
            byId('monitor-detail').hidden = false;
            await Promise.all(Object.keys(monitorHistory).map(kind => loadMonitorHistory(current, kind)));
        } catch (error) {
            if (selected === current) message('monitor-error', failure(error));
        }
    }

    async function loadMonitorHistory(current, kind, append = false) {
        const target = monitorHistory[kind];
        const more = byId(target.more);
        const host = byId(target.host);
        if (!append) host.replaceChildren();
        more.hidden = true;
        const before = append ? '?before=' + current.pages[kind] : '';
        const items = await api('/' + segment(current.id) + '/' + kind + before, { base: monitorRoot });
        if (selected !== current) return;
        renderList(host, items, item => {
            const details = [item.seq, item.kind && state(item.kind), state(item.state ?? item.status), state(item.signalEvent),
                item.reason, item.executionId];
            if (item.workflow) details.push('SOP ' + state(item.workflow.status), item.workflow.runId, item.workflow.reason);
            return item.workflow?.status === 'SUCCEEDED' && item.workflow.runId
                ? [record(details), button('读取结果', () => monitorResult(current, item.workflow.runId))]
                : [record(details)];
        }, '暂无记录', append);
        const seq = items.at(-1)?.seq;
        current.pages[kind] = seq;
        more.hidden = !seq || seq <= 1;
    }

    async function monitorResult(current, runId) {
        try {
            const data = await api('/' + segment(runId) + '/result', { base: runRoot });
            if (selected !== current) return;
            byId('monitor-result').textContent = format(data);
            byId('monitor-result').hidden = false;
        } catch (error) {
            if (selected === current) message('monitor-error', failure(error));
        }
    }

    refreshButton('monitor-refresh', loadMonitors);
    refreshButton('monitor-refresh-detail', () => { if (selected) selectMonitor(selected.id); });
    for (const [kind, target] of Object.entries(monitorHistory)) {
        refreshButton(target.more, async () => {
            const current = selected;
            if (!current?.pages[kind]) return;
            try { await loadMonitorHistory(current, kind, true); }
            catch (error) {
                if (selected === current) {
                    message('monitor-error', failure(error));
                    byId(target.more).hidden = false;
                }
            }
        });
    }
    refreshButton('monitor-transition', async event => {
        const current = selected;
        if (!current?.item || !token) return;
        const control = event.currentTarget;
        control.disabled = true;
        try {
            const action = current.item.state === 'PAUSED' ? 'resume' : 'pause';
            await api('/' + segment(current.id) + '/' + action, { base: monitorRoot, method: 'POST' });
            if (selected === current) await Promise.all([loadMonitors(), selectMonitor(current.id)]);
        } catch (error) {
            if (selected === current) message('monitor-error', failure(error));
        } finally {
            control.disabled = false;
        }
    });

    const releaseForm = fields('skill-form');
    const lookupForm = fields('skill-lookup');
    let skillEpoch = 0;
    let detailEpoch = 0;
    let skillCursor;

    async function loadSkills(append = false) {
        const epoch = ++skillEpoch;
        const host = byId('skill-list');
        const more = byId('skill-more');
        if (!append) host.replaceChildren();
        more.hidden = true;
        message('skill-error');
        if (!token) {
            host.append(button('连接 Skill 管理凭证', openAuth));
            return;
        }
        try {
            const page = await api(append ? '?after=' + segment(skillCursor) : '', { base: skillRoot });
            if (epoch !== skillEpoch) return;
            renderList(host, page.items, skill => [
                record([skill.skillKey, 'v' + skill.version, '修订 ' + skill.revision, state(skill.status)]),
                button('查看当前版本', () => readSkill(skill.skillKey, '', true))
            ], '暂无已发布 Skill', append);
            skillCursor = page.nextCursor;
            more.hidden = !skillCursor;
        } catch (error) {
            if (epoch === skillEpoch) {
                message('skill-error', failure(error));
                more.hidden = !append;
            }
        }
    }

    function showSkill(data) {
        lookupForm.key.value = data.skillKey;
        lookupForm.version.value = data.version;
        byId('skill-detail').textContent = format(data);
        byId('skill-detail').hidden = false;
    }

    async function readSkill(key, version = '', prepare = false) {
        if (!token) { openAuth(); return; }
        if (!key) { message('skill-error', '请填写 Skill Key。'); return; }
        const epoch = ++detailEpoch;
        message('skill-error');
        try {
            const data = await api('/' + segment(key) + (version ? '?version=' + segment(version) : ''), { base: skillRoot });
            if (epoch !== detailEpoch) return;
            showSkill(data);
            if (prepare) {
                releaseForm.key.value = data.skillKey;
                releaseForm.expectedRevision.value = data.revision;
                releaseForm.version.value = data.version + 1;
            }
        } catch (error) {
            if (epoch === detailEpoch) message('skill-error', failure(error));
        }
    }

    refreshButton('skill-refresh', () => loadSkills());
    refreshButton('skill-more', () => { if (skillCursor) loadSkills(true); });
    refreshButton('skill-current', () => readSkill(releaseForm.key.value.trim(), '', true));
    byId('skill-lookup').addEventListener('submit', event => {
        event.preventDefault();
        readSkill(lookupForm.key.value.trim(), lookupForm.version.value);
    });
    releaseForm.file.addEventListener('change', async () => {
        const file = releaseForm.file.files[0];
        if (!file) return;
        try {
            if (file.size > 262144) throw new Error('Markdown 文件过大。');
            const markdown = await file.text();
            if (releaseForm.file.files[0] !== file) return;
            if (markdown.length > 65536) throw new Error('Markdown 不能超过 65536 个字符。');
            releaseForm.markdown.value = markdown;
            message('skill-error');
        } catch (error) { message('skill-error', failure(error)); }
    });
    byId('skill-form').addEventListener('submit', async event => {
        event.preventDefault();
        if (!token) { openAuth(); return; }
        const credential = token;
        const submit = event.submitter;
        submit.disabled = true;
        message('skill-error');
        message('skill-status');
        try {
            const key = releaseForm.key.value.trim();
            const version = Number(releaseForm.version.value);
            const expectedRevision = Number(releaseForm.expectedRevision.value);
            const data = await api('/' + segment(key) + '/versions/' + segment(version) + '/publish', {
                base: skillRoot, method: 'POST', body: { expectedRevision, markdown: releaseForm.markdown.value }
            });
            if (token !== credential) return;
            detailEpoch++;
            showSkill(data);
            releaseForm.expectedRevision.value = data.revision;
            releaseForm.version.value = data.version + 1;
            message('skill-status', data.skillKey + ' v' + data.version + ' 已发布。');
            await loadSkills();
        } catch (error) {
            if (token === credential) message('skill-error', failure(error));
        } finally {
            submit.disabled = false;
        }
    });

    function loadCurrentPage() {
        if (!byId('monitors-page').hidden) loadMonitors();
        if (!byId('skills-page').hidden) loadSkills();
    }

    document.addEventListener('admin-page', loadCurrentPage);
    document.addEventListener('admin-connection', () => {
        monitorEpoch++;
        skillEpoch++;
        detailEpoch++;
        monitorDraftId = undefined;
        byId('monitor-reset-draft').hidden = true;
        selected = undefined;
        byId('monitor-detail').hidden = true;
        byId('monitor-result').textContent = '';
        byId('monitor-list').replaceChildren();
        byId('skill-list').replaceChildren();
        byId('skill-more').hidden = true;
        byId('skill-detail').textContent = '';
        byId('skill-detail').hidden = true;
        message('monitor-status');
        message('skill-status');
        if (reconcile.open) reconcile.close();
        loadCurrentPage();
    });
    loadCurrentPage();
})();
