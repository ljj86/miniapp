# 餐饮商城 · Shop 后续功能联调版

保留顾客竖屏商城、商家后台、平台管理三个入口。新增店铺客服、多附件留言、资料中心、说明书逐页预览、更新记录和平台配置，并提供真实 Java 模拟服务的 HTTP 接入。

## 运行前端

需要 Node.js 20.19+。

```sh
npm ci
npm run dev
npm run verify
```

默认打开 `http://127.0.0.1:5174`。三个角色仍可在 `/#/login` 使用 `111 / 111` 进入本机体验；本机数据保存在当前浏览器，各标签页分别选择身份。这是演示级隔离，不是生产鉴权。

顾客手机竖屏保留9:16容器和内部滚动；平板、电脑使用展开的多列顾客布局，手机横屏使用可滚动的紧凑宽版。它们始终在同一顾客路由中，不会进入商家或平台后台。断点与验证边界见 docs/RESPONSIVE-REVISION.md；本轮没有完成真实浏览器像素验收。

## Java 模拟服务模式

此源码包的根目录包含 Java 实现、约定和验证工具。先在根目录运行：

```sh
mvn -f verification/pom-with-guard.xml test
python3 verification/run-support-http-fixture.py --port 18081
```

保持服务运行，在前端登录页选择“连接Java模拟服务”，填入 `http://127.0.0.1:18081/api/v1`。测试身份是 `SIM-USER-001`、`SIM-OWNER-001`、`SIM-SUPPORT-001`；按本地模拟服务的 fixture 说明使用测试码。此流程不会发送真实短信，服务端按现有权限签发模拟会话；它不接受本机体验账号 111。令牌仅在内存中，刷新后重新连接。

服务端 fixture 只绑定本机地址，使用测试用原子 JSON 文件持久化；不是 MySQL 验收或生产服务。完整生产接线仍需验证既有 JDBC 存储和部署环境。

本轮 HTTP 已接客服会话/消息/已读、附件、留言处理/归档、资料和手册、AI/支付配置元数据、本人显示资料。商城目录、订单、额度、还款、售后等旧接口尚未映射到本界面的 Java 模式；进入这些页面会明确提示，不能静默改成本机成功。本机原有信用链路继续保留。

AI 与微信/银行卡/支付宝页保存展示配置，始终显示未接通，不接收真实密钥、证书，也不调用外部模型或资金通道。

## 真正的 HTTP 页面联调

从整包根目录，先执行 Maven 测试和前端 `npm ci`，再运行：

```sh
npm --prefix apps/dining-mall run test:dom
python3 verification/with-support-http-fixture.py --cwd apps/dining-mall -- node tests/backend-support-ui.test.mjs
python3 verification/with-support-http-fixture.py --cwd apps/dining-mall -- node tests/backend-resource-ui.test.mjs
```

每轮创建独立合成数据，启动实际 Java servlet，与真实 Vue/JSDOM 事件和 `fetch` 联调，结束后停服。测试不会连接线上用户数据。这证明接口和页面动作衔接，不代表浏览器像素、线上 Java、MySQL或生产安全验收。

附件支持图片、PDF、纯文本、MP4，单个512KiB，单次最多5个且合计1MiB。附件未做病毒扫描；下载需当前身份与记录权限。不要输入真实个人、交易、账户或密钥信息。

详细接入清单见整包根目录 `docs/SHOP-FEATURE-COVERAGE.md`，API字段见 `docs/SHOP-SUPPORT-CONTRACT.md` 和 `docs/SHOP-RESOURCE-CONTRACT.md`。
