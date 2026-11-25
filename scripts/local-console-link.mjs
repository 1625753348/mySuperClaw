import { randomBytes } from 'node:crypto';
import { readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const directory = process.argv[2] || process.env.PROGRAM001_CONSOLE_DIR;
if (!directory) throw new Error('请传入本机控制台的凭证目录。');

const info = JSON.parse(await readFile(join(directory, 'service.json'), 'utf8'));
const url = new URL(info.url);
if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.pathname !== '/tool-admin/index.html'
    || url.username || url.password || url.search || url.hash) {
    throw new Error('一键连接仅适用于绑定 127.0.0.1 的本机控制台。');
}
const now = Math.floor(Date.now() / 1000);
const expiresAt = Math.min(now + 15 * 60, Math.floor(Date.parse(info.expiresAt) / 1000));
if (!Number.isFinite(expiresAt) || expiresAt <= now) throw new Error('控制台凭证已过期，请重新启动测试服务。');

// 清理过期连接码，保留仍在有效期内的其他链接。
for (const name of await readdir(directory)) {
    if (!/^connect-[A-Za-z0-9_-]{43}\.ticket$/.test(name)) continue;
    const path = join(directory, name);
    const expiry = await readFile(path, 'utf8').catch(error => {
        if (error.code !== 'ENOENT') throw error;
        return '';
    });
    if (Number(expiry) <= now) await rm(path, { force: true });
}

const code = randomBytes(32).toString('base64url');
await writeFile(join(directory, 'connect-' + code + '.ticket'), String(expiresAt), { flag: 'wx', mode: 0o600 });
url.hash = 'connect=' + code;
const shortcut = join(directory, '一键连接.url');
await writeFile(shortcut, '[InternetShortcut]\r\nURL=' + url.href + '\r\n', { mode: 0o600 });
console.log(shortcut);
