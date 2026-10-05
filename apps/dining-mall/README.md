# 餐饮商城三端 · v0.2

基于用户此前开发的 Vue 3 / Element Plus 通用商城，适配餐饮顾客、商家和平台界面。详细边界见 [适配说明](docs/ADAPTER.md) 和仓库根目录 [更新日志](../../CHANGELOG.md)。

```sh
npm ci
npm run dev
npm run verify
```

Node.js 20.19+，本次使用 Node.js 24.19.0 / npm 11.9.0。`npm ci` 使用本目录锁文件，`npm run verify` 包含逻辑测试、实际 Vue DOM 点击测试和生产构建。

三个角色均通过 `/#/login` 使用 `111 / 111` 体验。顾客 `/#/mall/home`、商家 `/#/merchant/home`、平台 `/#/platform/home`。

业务状态为本机 `localStorage`，每个标签页身份使用 `sessionStorage`。不要输入真实个人、交易、账户或密钥信息。后端端点、支付通道、AI 服务仍待接入；体验身份不是生产鉴权。

`npm run build` 输出 `dist`，必须将其作为站点根目录托管；本版静态资源使用根路径。URL 路由在 `#` 后，默认不需要业务路由回退。托管域名、HTTPS、访问控制和安全响应头由部署环境负责，本包不包含私有托管配置。
