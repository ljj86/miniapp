# v0.2 构建与部署手册

源码包同时包含既有 Java 模拟项目和新餐饮 UI。以下命令应在解压后的仓库执行；不要把不同前端的产物覆盖到同一目录。

## 1. 新餐饮商城

环境：Node.js 20.19+。本次实测 Node.js 24.19.0、npm 11.9.0，依赖由 `package-lock.json` 固定。

```sh
cd apps/dining-mall
npm ci
npm run test:logic
npm run test:dom
npm run build
# 或一次运行全部检查和构建
npm run verify
# 本机开发/预览
npm run dev -- --host 127.0.0.1
npm run preview -- --host 127.0.0.1
```

`test:dom` 自动在本应用的 `.test-build` 内构建测试入口，使用锁定的 JSDOM 26.1.0，不依赖其他机器的 `/tmp` 工具目录。测试使用隔离虚拟浏览器存储，不读线上用户数据。

构建产物为 `apps/dining-mall/dist`。托管时将该目录作为站点根目录；当前静态资源使用 `/food`、`/brand` 等根路径，若部署到子路径必须先统一修改基础路径和资源引用再验证。Vue 路由使用 hash。

本包未携带私有站点绑定，也没有自动部署脚本。部署域名、HTTPS、访问控制和安全响应头由托管环境单独配置。`npm run build` 成功不能证明网站已经上线。

### 体验与数据

- `/#/login` 选择顾客/商家/平台，均为 `111 / 111`
- `/#/mall/home`、`/#/merchant/home`、`/#/platform/home` 为三端入口
- 本机存储键为 `mall-ui-data-v2`；清理站点数据会失去该浏览器内的演示操作记录
- 不输入真实姓名、电话、地址、交易或密钥；无真实支付、账户认证和跨设备状态服务

## 2. 既有轻量验证 UI

环境：Node.js 24、pnpm 11。依赖版本与已有 `pnpm-lock.yaml` 保持不变。

```sh
cd apps/simulation-ui
pnpm install --frozen-lockfile
pnpm contract:check
pnpm test
pnpm build
```

此界面面向原 Java 模拟接口，不等同于新增餐饮商城。真实运行需要另行配置隔离 Java 服务和身份/权限，不应将餐饮前端的 `111 / 111` 当作该后端的认证。

## 3. Java 聚焦测试

环境：Java 21、Maven 3.9.11。仓库根目录执行：

```sh
mvn -f verification/pom-with-guard.xml test
```

该入口仅编译并测试模拟领域及保护逻辑，不等同于完整上游项目或真实数据库集成。不要从聚焦测试通过推导出所有芋道模块通过。

完整选定后端模块的构建入口为：

```sh
cd upstream/backend
mvn -pl yudao-server -am -DskipTests package
```

本次 v0.2 发布没有重跑完整上游构建、MySQL/JDBC、HTTP 服务或正式部署，不将此命令列为通过。运行前须自行配置隔离环境，保留原安全保护，不使用 `REPLACE_ME` 作为有效凭据。

## 4. 原契约工具

```sh
python3 -m venv .venv-contract
. .venv-contract/bin/activate
pip install -r contract/requirements-validation.txt
cd contract
python generate_artifacts.py --check
python validate_artifacts.py --require-external
```

检查输出位于 `contract/qa`，不纳入版本。原冻结操作与新增 UI 本地动作必须分开，不能直接把通用商城的请求形状当作原 96 个端点。候选 SQL 不等于已执行的领域表迁移。

## 5. 源码打包

本版交付 ZIP 由已验证的 Git 提交直接归档，无依赖安装目录、`dist`、测试产物或运行数据库。需要重建源码包时：

```sh
git archive --format=zip --prefix=miniapp-v0.2/ -o miniapp-v0.2-source.zip <已核实的v0.2提交SHA>
```

上述为提交归档命令，不要求存在 Git tag。源码版本号为 `0.2.0`；正式 GitHub tag/Release 对象与源码交付分别记录，不能在未创建时声称已发布。
