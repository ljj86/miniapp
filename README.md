# miniapp

基于芋道官方开源源码开展的餐饮模拟「先吃后付」项目。

最初基线提交导入固定版本的开发源码、运行/构建必需资源与来源、清理记录；按项目需求排除可选的官方宣传和文档图片。业务开发已另行提交，以便清楚比较官方源码与项目修改。

## 源码目录

- `upstream/backend`：芋道 `ruoyi-vue-pro` Java 后端
- `upstream/admin`：芋道 `yudao-ui-admin-vue3` 管理后台
- `upstream/miniapp`：芋道 `yudao-mall-uniapp` 商城移动端/小程序
- `docs/upstream-lock.json`：官方仓库、固定提交与许可证摘要
- `docs/sanitization-report.json`：凭据清理清单及逐文件 SHA-256
- `docs/UPSTREAM-SOURCES.md`：导入范围、版本核验与使用说明
- `docs/omitted-upstream-artwork.json`：227 个可选宣传/文档图片的逐文件省略清单

原仓库的 README、LICENSE、作者及版权声明保留在各源码目录中。根目录未另外覆盖上游许可证。

上游示例口令、服务密钥、令牌、RSA 示例密钥和数据库种子用户的密码哈希已清理或禁用。请在隔离开发环境注入自己的配置后运行；不要把 `REPLACE_ME` 当作有效密钥。源码基线的清理检查不代表业务功能、依赖安全或生产上线已经验收。

上游工作流仅作为嵌套源码文件保留，本仓库根目录没有启用 GitHub Actions。运行产物、依赖安装目录、数据库数据、Git 元数据及本次扫描的原始敏感报告不在导入范围内。

## 已恢复的模拟开发检查点（2026-10-03）

业务源码在基线之后以独立提交 `aef61e6eacc17762b91600871baa87066181ebb7` 保存。它扩展原芋道后端，并提供 `apps/simulation-ui` 轻量 Vue 界面；这是明确标注未完成项的恢复检查点，尚不是完整 1.0 验收。

- [恢复范围、验证与未完成项](docs/RECOVERY-20261003.md)
- [0.1—1.0 全部150项当前状态（JSON）](docs/implementation-status-150.json)
- [同一状态表（CSV）](docs/implementation-status-150.csv)
- `verification/pom-with-guard.xml`：聚焦模拟领域与保护测试

截至2026-10-03T05:09Z，29项JUnit、8项Vue/API测试与构建、65个对账断言、40项静态契约检查通过；本机MySQL/Redis/Spring/Vue健康检查通过。规范38表、member身份桥接、部分后续业务扩展、规模与浏览器验收等仍未完成。候选参数不替代人工签审；微信分支按当前开发顺序延期。
