# 本机测试控制台一键连接

点击生成的 `一键连接.url` 会进入会话调试页，同时配置管理凭证与会话凭证。管理操作使用管理 JWT，会话和中间产物查询使用会话 JWT。

## 启用和生成链接

1. 启动本机 HTTP 实例时，在原有 `storage,gateway,intent` 等 Profile 后追加 `local-console`，设置 `--server.address=127.0.0.1`，并让 `ENTERPRISE_LOCAL_CONSOLE_DIRECTORY` 指向只供当前操作系统用户访问的凭证目录。Worker 使用原有启动配置。
2. 启动器在该目录写入 `管理凭证.txt`、`会话凭证.txt` 和 `service.json`。元数据包含 `url`（控制台页面完整地址）及 `expiresAt`（两枚 JWT 中较早的过期时间，ISO 8601）。
3. 在项目目录执行 `node scripts/local-console-link.mjs "<凭证目录>"`。双击输出的 Windows 快捷方式打开控制台。连接码最多有效 15 分钟，用过或页面刷新后重新生成链接即可。

## 连接过程

生成脚本将随机码放在 URL 片段，并在凭证目录写入同名票据。页面读取片段后将其替换成 `#chat`，再通过同源 POST 交换凭证。Spring 处理器验证本机地址、浏览器 Origin、过期时间和票据是否可消费。并发交换时，仅成功删除票据的请求可以拿到凭证。

浏览器分别请求管理与会话 API 验证 JWT，验证通过才同时接入。JWT 随后仅保存在页面内存中，刷新后可重新生成快捷方式或使用页面上两个连接按钮。默认部署继续使用各自的 Bearer JWT 入口，服务端内置一键交换由本机 Profile 显式启用。
