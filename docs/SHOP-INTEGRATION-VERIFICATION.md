# 前后端联调验收与复跑

快照日期：2026-10-05。基线提交：77bd867b5884779a00f5fe2d6e4d74143db424b7。此目录记录可复核源码联调证据；GitHub提交不表示网站或Java服务已经部署。

## 结果

前端依赖锁文件安装、完整 `npm run verify` 和生产构建通过。普通商城12场景、角色路由58项、信用契约13组、售后6组、本机服务30项保持通过；真实Vue组件完成评价/售后三端流程、客服/留言三端流程。

另有会话切换6项、排队模式切换6项、顾客/工作台消息历史6项、当前顾客与上传组件生命周期13项回归通过。迟到响应不串资料，切到后端后不执行旧本机写入；聊天轮询先补齐历史缺口，超界时不误推进已读。

Java聚焦测试95项全部通过，原财务13场景242断言仍通过。前端使用真正的 `fetch` 和实际 Java servlet 运行两个页面联调脚本：

- 客服/留言：服务端身份与店铺ID、顾客图片消息→商家回复→顾客读取，PDF/文字/MP4三附件留言、长标题与HIGH优先级、商家处理、顾客归档/恢复、归档后的商家附件权限撤销、平台回复、本人资料、无本机回退
- 资料/配置：8组页面流程、71次support请求，真实PDF/PNG MIME与hash、手册翻页、文字材料、更新记录、自定义资料创建/更新/历史/归档、只读内置资料、AI与BANKCARD/WECHAT配置始终未连接、本人资料保存再读取

## 环境与命令

需要 Node.js 20.19+、Python3、Maven和兼容原项目的JDK（源码目标Java8）。先从本包根目录执行：

```sh
mvn -f verification/pom-with-guard.xml test
npm --prefix apps/dining-mall ci
npm --prefix apps/dining-mall run verify
python3 verification/with-support-http-fixture.py --cwd apps/dining-mall -- node tests/backend-support-ui.test.mjs
python3 verification/with-support-http-fixture.py --cwd apps/dining-mall -- node tests/backend-resource-ui.test.mjs
```

HTTP runner读取上述Maven生成的classpath，启动只绑定127.0.0.1的真实servlet；每次使用独立合成状态并在结束后停服。无需云账户或真实支付凭证。不要将生成的runtime状态、target或node_modules发布。

## 边界

这些测试覆盖真实Vue DOM事件和实际Java HTTP，但JSDOM没有真实浏览器布局引擎，不能证明像素效果或9:16实际尺寸。未进行浏览器视觉、MySQL/JDBC、全upstream构建、线上Java、生产安全/压测、外部AI/短信/支付或病毒扫描验收。

生产构建成功并提示部分依赖分块大于500kB；后续可进一步拆分编辑器/图表等依赖。测试通过不意味着站点已更新；实际站点发布由托管平台单独验证。

完整功能映射见 `SHOP-FEATURE-COVERAGE.md`，机器可读结果见 `SHOP-INTEGRATION-RESULTS.json`。
