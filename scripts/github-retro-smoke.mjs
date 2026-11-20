import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const skill = await readFile(new URL('../skills/github-project-retrospective/SKILL.md', import.meta.url), 'utf8');
const manifest = JSON.parse(skill.split('```json')[1].split('```')[0]);
const fixture = JSON.parse(await readFile(new URL('./fixtures/github-retrospective.json', import.meta.url), 'utf8'));

// 此处用确定性的夹具语句解析与标签排序回放契约。应用层识别、模型和网关将在集成测试中替换这些端口。
function recognize(text) {
    const project = text.match(/[‘“「]([^’”」]+)[’”」]/)?.[1];
    assert.ok(project && /最近一个月/.test(text) && /Git 提交记录/.test(text)
        && /Issue 讨论/.test(text) && /Markdown.*复盘/.test(text));
    return { project, days: manifest.inputSchema.properties.days.default };
}

function render({ project, from, to, findings }) {
    const lines = [
        `# ${project}｜项目复盘`,
        '', '## 取证范围',
        `模拟仓库 \`${fixture.repository}\` · 分支 \`${fixture.ref}\``,
        `UTC 窗口 ${from} 至 ${to}，起点包含、终点排除。`,
        `读取 ${findings.issueCount} 条 Issue 和 ${findings.commitCount} 条提交。`,
        '', '## 最值得复盘的问题'
    ];
    for (const [index, item] of findings.items.entries()) {
        const repoUrl = 'https://github.com/' + fixture.repository;
        const issueLink = `[Issue #${item.number} 讨论](${repoUrl}/issues/${item.number}#issuecomment-${item.commentId})`;
        const commitLinks = item.commits.map(sha => `[提交 ${sha.slice(0, 7)}](${repoUrl}/commit/${sha})`);
        lines.push('', `### ${index + 1}. ${item.title}`, `- 现象与影响 · ${item.impact}`,
            `- 根因或待确认项 · ${item.cause}`, `- 证据 · ${[issueLink, ...commitLinks].join('、')}`,
            `- 改进与验收 · ${item.action}`);
    }
    lines.push('', '## 证据缺口', findings.items.length < 3
        ? `证据支持 ${findings.items.length} 项，剩余项待补充。`
        : '本次验收使用合成数据，证据链接用于验证报告结构。');
    return lines.join('\n') + '\n';
}

function fixtureHandlers(context, complete = true) {
    const inWindow = value => Date.parse(value) >= Date.parse(context.from) && Date.parse(value) < Date.parse(context.to);
    return {
        'github/search_issues@1': async args => {
            assert.deepEqual(args, { repository: context.repository, from: context.from, to: context.to });
            return { complete, items: fixture.issues.filter(issue => inWindow(issue.updatedAt)) };
        },
        'git/log@1': async args => {
            assert.deepEqual(args, context);
            return { complete: true, items: fixture.commits.filter(commit => inWindow(commit.date)) };
        },
        'retrospective.synthesize@1': ({ issues, commits }) => {
            if (!issues.complete || !commits.complete) throw new Error('EVIDENCE_INCOMPLETE');
            const items = issues.items.filter(issue => issue.severity > 0).sort((a, b) =>
                b.severity - a.severity || a.number - b.number).slice(0, 3).map(issue => ({
                    ...issue, commits: commits.items.filter(commit => commit.issue === issue.number).map(commit => commit.sha)
                }));
            return { items, issueCount: issues.items.length, commitCount: commits.items.length };
        },
        'retrospective.render_markdown@1': render
    };
}

async function replay(input, context, handlers) {
    const produced = {};
    const data = { input, context, steps: produced };
    const read = ref => ref.split('.').reduce((value, key) => value?.[key], data);
    const pending = new Map(manifest.nodes.map(node => [node.id, node]));
    assert.equal(pending.size, manifest.nodes.length, '节点 ID 必须唯一');

    while (pending.size) {
        const ready = [...pending.values()].filter(node => node.dependsOn.every(id => Object.hasOwn(produced, id)));
        assert.ok(ready.length, '存在环或缺失依赖');
        await Promise.all(ready.map(async node => {
            const args = Object.fromEntries(Object.entries(node.inputs).map(([key, ref]) => {
                const value = read(ref);
                assert.notEqual(value, undefined, `引用无效 ${node.id} ${ref}`);
                return [key, value];
            }));
            produced[node.id] = { output: await handlers[node.tool ?? node.handler](args) };
            pending.delete(node.id);
        }));
    }
    return { markdown: read(manifest.output), steps: produced };
}

const input = recognize(fixture.userInput);
assert.equal(input.project, fixture.project);
assert.equal(input.days, 30);
assert.equal(manifest.executionMode, 'WORKFLOW');
assert.equal(manifest.intentDefinition, 'PROJECT.RETROSPECTIVE@1');
assert.deepEqual(manifest.nodes.map(node => [node.id, node.dependsOn]),
    [['issues', []], ['commits', []], ['findings', ['issues', 'commits']], ['report', ['findings']]]);

const context = {
    repository: fixture.repository,
    ref: fixture.ref,
    from: new Date(Date.parse(fixture.now) - input.days * 86_400_000).toISOString(),
    to: fixture.now
};
const normalHandlers = fixtureHandlers(context);
const result = await replay(input, context, normalHandlers);
assert.deepEqual(result.steps.findings.output.items.map(item => item.number), fixture.expectedTop3);
for (const id of fixture.expectedTop3) {
    const { commentId } = fixture.issues.find(issue => issue.number === id);
    const { sha } = fixture.commits.find(commit => commit.issue === id);
    assert.ok(result.markdown.includes(`/issues/${id}#issuecomment-${commentId}`));
    assert.ok(result.markdown.includes('/commit/' + sha));
}
assert.ok(!result.markdown.includes('4444444'));
await assert.rejects(replay(input, context, fixtureHandlers(context, false)), /EVIDENCE_INCOMPLETE/);
const sparse = await replay(input, context, {
    ...normalHandlers,
    'github/search_issues@1': () => ({ complete: true, items: fixture.issues.slice(0, 1) })
});
assert.match(sparse.markdown, /证据支持 1 项/);
assert.equal((sparse.markdown.match(/^### /gm) || []).length, 1);

const report = resolve(process.argv[2] || 'target/github-project-retrospective-example.md');
await mkdir(dirname(report), { recursive: true });
await writeFile(report, result.markdown);
console.log('固定 DAG 正常、取证失败、证据不足三种回放通过');
console.log(report);
