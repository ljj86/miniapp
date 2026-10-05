# miniapp · v0.2 持续修订

结合项目所有者此前开发的购物商城，在原芋道餐饮「先吃后付」模拟基础上，完善顾客、商家和平台三端。当前源码同时包含本机商城演示，以及已通过真实回环 HTTP 联调的 Java 客服/留言、附件、资料、手册、配置元数据和本人显示资料接口。

本次以 `77bd867b5884779a00f5fe2d6e4d74143db424b7` 为基线，合入此前交付但未进入 GitHub 的客服功能、Java接线、手机/平板响应式修订及品牌检查。版本继续为 `0.2.0`，未创建新 tag 或 GitHub Release。

对应149文件前端已发布到 [鲜食好店体验页](https://mall-original-ui.lijunjie050307.chatgpt.site)，源身份与本次GitHub应用目录一致；默认仍是本机演示。代码提交和静态站点发布不代表 Java 云服务已部署。真实支付、短信、外部 AI 和生产身份服务仍未接通；商城目录/购物车/订单/额度/还款/售后等原有流程尚未全部映射到新前端的 Java 模式。

- [更新日志](CHANGELOG.md)
- [功能覆盖与未接入清单](docs/SHOP-FEATURE-COVERAGE.md)
- [构建与部署](docs/BUILD-DEPLOY-v0.2.md)
- [此次修订验证](docs/REVISION-VERIFICATION-20261005.md)
- [Java支持接口](docs/SHOP-SUPPORT-CONTRACT.md)、[资料接口](docs/SHOP-RESOURCE-CONTRACT.md)
- [源码与许可来源](docs/SOURCE-PROVENANCE-v0.2.md)

## 快速运行

Node.js 20.19+，锁文件固定依赖；已验证环境为 Node.js 24.19.0 / npm 11.9.0。

```sh
cd apps/dining-mall
npm ci
npm run dev
npm run verify
```

顾客入口 `/#/mall/home`、商家 `/#/merchant/home`、平台 `/#/platform/home`。默认通过 `/#/login` 使用 `111 / 111` 选择本机体验身份，数据保存在本浏览器，不是生产认证或跨设备服务。

顾客手机竖屏保留9:16容器和内部滚动；平板/电脑展开为宽版，低高度横屏采用可滚动紧凑布局。仍使用同一顾客路由，不会因屏幕变化进入商家/平台后台。详见 [响应式规则](apps/dining-mall/docs/RESPONSIVE-REVISION.md)。

## Java 模拟服务

```sh
mvn -f verification/pom-with-guard.xml test
python3 verification/run-support-http-fixture.py --port 18081
```

随后在前端登录页明确选择“连接Java模拟服务”，使用 `http://127.0.0.1:18081/api/v1` 及该夹具说明中的合成身份流程。服务端签发模拟会话，令牌只在前端内存中；刷新需重新连接。本机演示的 `111 / 111` 不能代替 Java 身份认证。

当前实际 HTTP 连接范围是客服会话/消息/已读、附件、留言处理/归档恢复、资料/手册、AI/支付展示元数据及本人显示资料。未映射的商城/信用页面明确提示，不静默回退到本机成功。测试服务只绑定回环地址，使用测试用原子 JSON 存储，未完成 MySQL/JDBC 或线上部署验收。

## 目录

| 目录 | 作用 |
| --- | --- |
| `apps/dining-mall` | 用户原商城融合后的餐饮三端、响应式界面、本机适配与明确选择的 Java 支持接口 |
| `apps/simulation-ui` | 原 Java 模拟业务的轻量验证界面 |
| `upstream/backend` | 固定芋道 Java 基线与项目模拟领域、客服/资料接口实现 |
| `upstream/admin` | 固定版本芋道 Vue 管理后台 |
| `upstream/miniapp` | 固定版本芋道 uni-app 商城源码 |
| `contract` | 原冻结76项操作及状态规则；新增支持接口单独注册 |
| `verification` | 聚焦 Java、财务场景及真实回环 HTTP 夹具/脚本 |

三套前端各自保留用途。餐饮 UI 参考芋道商城视觉，但运行实现来自用户原商城的适配，并非未修改的芋道原版。

## 边界与来源

测试包含真实 Vue DOM 事件、Java servlet 和 HTTP，不等于浏览器像素、手机实机、完整上游、MySQL、生产安全或压测验收。附件类型与签名检查不等于病毒扫描；不要输入真实个人、交易、账户或密钥信息。

原项目的 README、LICENSE、作者与固定版本记录保持原样。各组件和素材权利分别适用，不对组合项目擅自追加统一开源许可证。历史 v0.2 初版说明与测试范围保留在更新日志及 `docs/RELEASE-VERIFICATION-v0.2.md` 中，最新接口范围以本页和覆盖清单为准。
