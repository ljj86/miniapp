# miniapp · v0.2

在芋道餐饮「先吃后付」模拟项目的基础上，结合项目所有者此前开发的购物商城，完善顾客、商家、平台三端的餐饮体验与本机业务演示。

本版保留既有 Java 后端、芋道上游源码和轻量验证界面，另在 `apps/dining-mall` 交付融合原商城的新前端。新前端仍使用浏览器本机状态，尚未接入真实 Java 服务端、原 96 个端点或真实支付；不能用于真实资金、授信或生产账户。

- [v0.2 更新日志](CHANGELOG.md)
- [安装、测试、构建与部署](docs/BUILD-DEPLOY-v0.2.md)
- [本次发布验证与边界](docs/RELEASE-VERIFICATION-v0.2.md)
- [源码、素材与许可来源](docs/SOURCE-PROVENANCE-v0.2.md)

## 快速体验新增餐饮应用

使用 Node.js 20.19+；本次验证环境为 Node.js 24.19.0 / npm 11.9.0。

```sh
cd apps/dining-mall
npm ci
npm run dev
```

- 顾客端：`/#/mall/home`
- 商家端：`/#/merchant/home`
- 平台端：`/#/platform/home`
- 角色入口：`/#/login`，三个角色账号与密码均为 `111 / 111`

账号只切换演示身份。业务数据保存在同一浏览器的 `localStorage`，不跨设备同步；不要输入真实个人资料或支付信息。

```sh
npm run verify
```

上述命令运行数据适配、角色路由、原业务规则、跨角色流程、售后/评价、实际 Vue DOM 事件测试，并构建静态站点。它不启动 Java，也不代表真实后端端到端或像素布局验收。

## 源码目录

| 目录 | 作用 |
| --- | --- |
| `apps/dining-mall` | 本次融合用户原商城的餐饮三端应用；Vue 3 / Element Plus / 本机适配层 |
| `apps/simulation-ui` | 既有 Java 模拟后端的轻量验证 UI；本版同步此前交付的草稿、通知、日期等源码增量 |
| `upstream/backend` | 固定版本芋道 `ruoyi-vue-pro` Java 后端，项目模拟业务扩展在 `yudao-server` |
| `upstream/admin` | 固定版本芋道 `yudao-ui-admin-vue3` 管理后台；本版恢复 OA 常量文件 |
| `upstream/miniapp` | 固定版本芋道 `yudao-mall-uniapp` 原商城移动端/小程序 |
| `contract` | 原冻结接口、状态机、候选数据字典和验证工具；不是已经执行的生产迁移 |
| `verification` | 聚焦模拟领域与保护测试的 Maven 入口及历史验证脚本 |

三套前端保留各自用途，不能混用登录、接口或构建产物。餐饮 UI 的视觉参考来自芋道商城，运行实现是用户原 Vue 商城的适配，不能称为未修改的芋道原版。

## 原有后端增量

同步此前源代码交付中的时间/键集分页、上海时区日期边界、游标身份与权限绑定、历史商户申请恢复及其回归测试。原 76 项冻结操作保持不变；补充接口与 UI 本地适配动作分别管理。

生产身份映射、规范领域表迁移、真实支付和外部渠道、完整上游构建、性能与跨机器部署验收仍未完成。历史 150 项状态与运行证据保留在已有 `docs` 中，不能当作 v0.2 新验收结论。

## 版本与许可

`VERSION` 为 `0.2.0`。本次日志以原 `main` 的 `dad246de9ae2f3888bad454cfac402a2eb7ef3af` 为比较基线；发布前没有 v0.1 标签或 GitHub Release。源码提交和可下载源码包不表示网站已部署，也不自动创建 GitHub Release。

上游 README、LICENSE、作者和固定提交记录保持原样，详见 [上游来源](docs/UPSTREAM-SOURCES.md)。不对整个组合项目追加统一开源许可证。用户提供商城源与素材的再分发/商用许可须按各自权利确认。
