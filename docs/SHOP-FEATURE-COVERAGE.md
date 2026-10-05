# 鲜食好店响应式修订 · 功能覆盖与验证说明

核查日期：2026 年 10 月 5 日

当前布局规则已更新：手机竖屏9:16，平板/电脑宽版，手机横屏紧凑宽版。44个路由结构与12组CSS视口策略检查通过；实际浏览器视觉仍未验收。详见 `apps/dining-mall/docs/RESPONSIVE-REVISION.md`。以下v7业务接入范围保持不变。

v7 已补齐资料中心、真实手册分页与下载、图片及文件及视频附件、三级留言、可恢复归档、资料版本历史和平台显示配置。新 Vue 页面已通过实际 HTTP 调用 Java 模拟服务，完成客服、留言、资料、配置和个人显示资料的联调。

本次交付是可继续维护的前端与 Java 源码；GitHub提交与站点、Java服务部署分别验证。它没有把整个商城改成服务端版本：原商城、先吃后付、评价和售后仍提供完整的本机演示；Java 模式只开放已明确映射的服务功能。真实支付、真实短信和外部 AI 调用均未开启。

本文中的文件位置均为源码包内的相对路径。前端目录为 `apps/dining-mall`；Java 模块位于 `upstream/backend/yudao-server`。

## 两种数据模式

| 模式 | 可以使用的范围 | 身份与保存位置 | 明确边界 |
|---|---|---|---|
| 本机演示 | 餐品、购物车、订单、地址、收藏、先吃后付、评价、售后，以及新增客服、留言、资料和配置 | 三端体验账号均为 `111 / 111`；数据保存在当前浏览器 | 不代表真实订单、资金、短信或模型服务；换浏览器不共享数据 |
| Java 模拟服务 | 授权店铺上下文、客服会话、留言、附件、资料、平台配置、个人显示资料 | 使用服务端签发的模拟会话；请求携带 Bearer 令牌；服务端校验当前身份与店铺权限 | 旧商城与信用接口未映射时明确拒绝；失败不转成本机成功，也不混写两种模式的数据 |

Java 连接只接受 `SIM-` 开头的模拟账号，并使用已有挑战码和会话接口。`111 / 111` 不是 Java 登录凭据。令牌保留在运行时内存中；刷新后需要重新连接，页面不会从本机存储恢复一份伪造的服务端身份。非本机连接要求 HTTPS；仅回环地址允许 HTTP。

## 已实现的功能

| 功能 | v7 前端行为 | Java 实现与当前范围 | 主要源码依据 |
|---|---|---|---|
| 服务中心与旧入口兼容 | 顾客可进入店铺客服、我的留言、使用手册、帮助资料和更新记录；旧 `manual`、`updatelog` 路由转到新页面 | `/support/context` 返回真实服务端店铺 ID 和可关联订单；不拿本机店铺 ID 替代 | `src/components/SupportCustomer.vue`、`src/router/index.js`、`SupportCatalog.java` |
| 店铺客服与未读 | 顾客发起会话；商家、平台查看与回复；显示未读、显式已读和刷新状态 | 服务端按顾客、店铺授权或全局客服权限隔离；发送者由会话身份派生 | `SupportCustomer.vue`、`src/views/back/SupportWorkspace.vue`、`SupportModule.java` |
| 消息历史 | 可读取更早消息；刷新合并历史，避免把中间缺失的消息直接跳过 | 每次最多返回 100 条，使用 `beforeId` 继续；已读使用实际显示的 `lastSeenMessageId` | `src/utils/service-history.js`、`SupportModule.java` |
| 图片、文件和视频 | 统一多附件选择器；图片展开查看、MP4 预览、文件下载；显示名称、类型、大小和未扫描提示 | 图片、PDF、TXT、MP4 的受限上传与授权下载；附件使用不透明 ID，不接受外部文件 URL | `src/components/ServiceUpload.vue`、`ServiceAttachment.vue`、`SupportAttachments.java` |
| 留言字段与多轮回复 | 标题最长 200 字；高、中、低三级；可关联本店订单；原留言和回复均可带附件 | 版本校验、幂等提交、追加回复和不可覆盖的处理历史；旧 NORMAL/IMPORTANT 映射为 MEDIUM/HIGH | `SupportCustomer.vue`、`SupportWorkspace.vue`、`SupportModule.java` |
| 留言搜索、筛选与分页 | 商家及平台按状态、等级、文字筛选；平台可按店铺筛选；列表展示附件数量及时间；10/20/50 条分页 | 筛选和分页在已授权范围内执行；顾客仅自己的记录，商家仅本店记录 | `SupportWorkspace.vue`、`GET /support/overview` |
| 留言处理与重新打开 | 处理中、已解决、已关闭；按允许的动作重新打开 | 服务端检查状态和当前版本；冲突不覆盖已有记录；留言操作不改变订单、额度、应收或退款 | `SupportModule.java`、`docs/SHOP-SUPPORT-CONTRACT.md` |
| 可恢复归档 | 顾客和平台可归档、显示含归档列表并恢复；保留原状态及处理记录 | 归档后只有留言本人和全局平台客服可读或恢复；商家失去该留言及附件的访问权；没有硬删除接口 | `SupportCustomer.vue`、`SupportWorkspace.vue`、`SupportModule.java` |
| 手册目录、分页与下载 | 三份本项目使用指南；每份两页 PNG 预览和真实 PDF；目录封面、页码、前后翻页、文字版、下载及错误重试 | 受控内置资源清单校验名称、类型、大小与 SHA-256；通过鉴权 JSON 接口传回文件字节 | `src/components/HelpCustomer.vue`、`SupportManualAssets.java` |
| 帮助资料与更新记录 | 可搜索、按适用对象筛选、分页；更新记录使用时间线呈现 | MANUAL、KNOWLEDGE、UPDATE_LOG 三种资料；内置说明明确属于源码能力记录，不冒充部署日志 | `HelpCustomer.vue`、`SupportResources.java` |
| 平台资料管理 | 新建、编辑、归档自定义资料；查看版本、操作时间和历史快照；内置资料只读 | 全局平台客服才能维护；同事务追加快照与哈希；归档内容从顾客列表移除，历史仍保留 | `src/views/back/ServiceSettings.vue`、`SupportResources.java` |
| AI 配置页面 | 可保存模型名、欢迎语、启用意向；页面持续显示未连接 | 仅保存非秘密配置；`NOT_CONNECTED`、`externalCallsEnabled:false`、`secretConfigured:false` 保持不变 | `ServiceSettings.vue`、`SupportResources.java` |
| 支付渠道配置页面 | MOCK、支付宝、微信、银行卡分别展示；可保存显示开关和名称 | MOCK 仅表示模拟能力可用；真实渠道始终未连接、不能扣款；没有密钥、证书、网关或回调接入 | `ServiceSettings.vue`、`SupportResources.java` |
| 本人显示资料 | Java 模式展示实际模拟账号，只读；可保存本人昵称、邮箱和联系电话 | `/support/profile` 只允许当前身份操作；显示资料与登录、财务身份分离；头像目前限定默认资产 | `src/utils/service-mode.js`、`SupportProfileModule.java` |
| 原商城与信用演示 | 保留既有餐饮外观、顾客竖屏容器、商家及平台页面，以及额度、确认、核销、还款、退款、评价、售后流程 | 原 Java 模拟领域的回归仍保留；这些旧商城页面本次没有新增完整 Java API 映射 | `src/utils/credit-service.js`、`feedback-service.js`、`request.js` |

表中的前端短文件名均相对于 `apps/dining-mall/src`；Java 短文件名位于 `upstream/backend/yudao-server/src/main/java/cn/iocoder/yudao/server/simulation`。

## 仍未完成或有意保持关闭的范围

| 项目 | 当前准确状态 | 后续需要完成的工作 |
|---|---|---|
| 商家店铺联系资料 | 本轮未接入。当前 Java 已验证的是账号本人的显示资料；商家地址、店铺说明在后端模式禁用，不能算已保存的店铺资料 | 增加按授权店铺读取和版本化保存的独立契约，并完成商家页面联调；不能把账号联系电话当作完整店铺资料 |
| Java 版商品、购物车、结算、订单及信用页面 | 现有页面在本机模式可体验；Java 模式明确提示未映射 | 逐项对接原 Java 商务和财务契约，统一 ID、状态、幂等与金额规则，再进行跨端验收 |
| 真实注册、改密与短信 | 未开放；Java 使用合成账号和测试挑战码；原密码页面没有成为真实改密服务 | 生产身份服务、注册验证、密码安全与会话失效策略；真实短信供应商接入 |
| 外部 AI 自动回复与连接测试 | 未开启，也不会返回虚构的连接成功 | 服务端秘密管理、供应商请求、错误与超时处理、调用审计和费用限制 |
| 真实支付、退款和资金结算 | 未接支付宝、微信或银行卡；显示配置不能开启真实交易 | 商户及支付凭证配置、回调核验、金额和订单匹配、退款与对账闭环 |
| SKU 和优惠券 | 后续手机版原包没有实际 SKU 或优惠券业务，不能算漏迁的可运行功能 | 独立设计规格、价格与库存快照，以及优惠券资格、核销和金额计算 |
| 自定义手册的 PDF 生成 | 自定义手册当前是纯文字，可下载 TXT；不会自动生成 PDF | 如需 PDF，应增加明确的生成、文件存储与版本对应流程 |
| 资料归档恢复 | 自定义资料可归档并保留历史；当前资料接口没有恢复动作 | 如需要恢复资料，增加独立权限、状态规则及界面；不要与已实现的留言恢复混淆 |
| 生产部署与存储 | 源码和测试夹具可用；GitHub源码更新不表示已部署，未完成 MySQL 运行验收 | 部署配置、数据库验证、生产身份映射、持久文件存储及上线验收 |

## 可见的数据和文件边界

- 单附件上限为 512 KiB；每次消息、留言或回复最多 5 个不同附件，合计不超过 1 MiB。支持 PNG、JPEG、GIF、WebP、PDF、UTF-8 TXT、MP4。
- Java 模拟存储另有累计配额：每账号 8 MiB、128 个附件；每个模拟数据集合 32 MiB、1024 个附件，包含尚未提交到会话的上传。配额不足时明确报错；当前没有自动清理或文件永久删除流程。
- 附件状态始终为 `UNSCANNED_SIMULATION`。类型、文件头和容器检查不等于病毒扫描。PDF 附件以用户主动下载为主，不自动作为 HTML 或嵌入文档执行；视频预览需点击加载。
- 上传后、绑定前仅上传者可读。绑定之后每次读取都重新检查会话或留言权限，归档也会同步收紧附件权限。显示名称不会成为服务器文件路径。
- 消息每页最多 100 条。发现刷新结果与旧历史不连续时，会尝试有界补读；仍不能补齐时显示需要重读的提示，并且不推进已读位置。
- 留言详情保留完整回复与处理历史。列表和写入响应使用精简字段；写入已成功但详情刷新失败时提示“操作已保存，详情待刷新”，不以重发写入代替刷新。
- 资料正文、标题、欢迎语和历史快照按纯文字显示。资料“顾客／商家／全部”是内容分类，不是保密权限：所有有效登录用户可阅读全部公开的活动资料。资料管理和历史接口仅限全局平台客服。
- 内置指南只读。自定义资料最多 500 条，包含归档记录；标题最多 160 字、正文最多 16000 字。资料和配置修改保留追加式历史；资料界面可查看快照，配置历史当前由后端保存。
- 本人的邮箱和联系电话不会作为客服公开身份返回给其他用户；对外客服显示只使用昵称及头像。账号显示资料不修改登录号码、UID、凭据或财务身份。

Java 代码沿用 `JdbcSimStore` 的 `sim_v1_partition` JSON 聚合与事务锁，新记录按需加入聚合。这不是新建的一套生产级关系表或对象存储。当前 HTTP 联调使用独立的原子 JSON 测试存储；没有用该结果替代 MySQL 实际运行验证。

## 已有验证证据

| 检查 | 结果与能够证明的内容 | 不能据此声称的内容 |
|---|---|---|
| 前端完整回归与构建 | `npm run verify` 通过；包含本机服务 30 项、原商城 12 项、路由 58 项、信用 13 组、售后 6 项及实际 Vue DOM 操作检查；构建完成 | 大体积 chunk 提示仍是构建警告；没有因此宣称加载性能已优化 |
| 模式切换与异步竞态 | 模式锁 6 项、服务模式 6 项、两端历史组件 6 项、顾客异步竞态 13 项通过；覆盖身份或模式切换后旧请求不能写入新上下文，以及历史缺口不能推进已读 | 不代表所有网络故障和所有并发规模均已穷尽 |
| Java 聚焦测试 | 17 个套件，95 项测试，0 失败、0 错误、0 跳过；其中包含新增客服、附件、资料、手册、配置、本人资料和 HTTP 测试 | 不代表完整上游工程构建或 MySQL 运行通过 |
| 既有商务与财务回归 | 13 个场景、242 项断言通过 | 不代表真实资金渠道已连接 |
| Vue DOM 到 Java 的客服联调 | 实际 Vue 事件与 HTTP：服务端店铺身份、顾客上传图片、商家回复、三类文件工单、超过 80 字标题、高优先级、版本化处理、归档恢复、权限撤回、平台回复、本人资料；确认 Java 模式未写入本机商城数据 | JSDOM 不证明浏览器像素、真实视频解码、移动设备或多浏览器表现 |
| Vue DOM 到 Java 的资料联调 | 8 组场景通过，71 次服务相关 HTTP 请求：手册分页和 PDF 字节、自定义资料创建编辑历史、知识与更新记录、TXT 下载、资料归档、AI 元数据、微信及银行卡元数据、本人资料 | 下载字节校验不代表浏览器原生下载界面已验收；开关保存不代表供应商接通 |
| 实际传输层与 Java HTTP | 使用真实 HTTP 传输层，验证认证、范围隔离、幂等、分页和文件哈希；测试服务重建后读取原子 JSON 状态 | 不代表已托管的 Java 服务或生产身份系统通过验收 |

客服与资料联调测试分别为 `apps/dining-mall/tests/backend-support-ui.test.mjs` 和 `apps/dining-mall/tests/backend-resource-ui.test.mjs`，在最终竞态修复后均已完整重跑通过。Java 结果可查看 `verification/target-with-guard/surefire-reports`。本说明核对已有结果与当前源码，没有把未运行的浏览器、像素、MySQL 或生产检查标成通过。

## 复现命令

要求：Node.js 20.19 或更高版本、项目指定的 npm 版本、兼容 Java 8 源码目标的 JDK、Maven 和 Python 3。以下命令从合并后的源码包根目录执行；测试使用合成账号与临时状态。

安装前端依赖并执行原有回归和构建：

```bash
npm --prefix apps/dining-mall ci
npm --prefix apps/dining-mall run verify
```

合并后的 `npm run verify` 已包含本次新增的本机客服与资料领域、HTTP 传输、历史补读、模式锁、服务模式与顾客竞态测试，以及本机客服 DOM 流程。它会先构建 `.test-build`，供后续实际 HTTP DOM 测试使用。实际 Java HTTP 联调需要下面的 Java 夹具，不包含在离线前端检查中。

编译和运行 Java 聚焦测试，然后通过真实回环 HTTP 执行两组 Vue DOM 联调：

```bash
mvn -f verification/pom-with-guard.xml test
python3 verification/with-support-http-fixture.py --port 18085 --cwd apps/dining-mall -- node tests/backend-support-ui.test.mjs
python3 verification/with-support-http-fixture.py --port 18086 --cwd apps/dining-mall -- node tests/backend-resource-ui.test.mjs
```

测试运行器为每次执行创建全新的合成状态，等待 Java HTTP 就绪，设置测试 API 地址，并在结束后停止服务。它使用真实 Java Servlet 和领域代码，但使用明确的测试存储；服务就绪本身不等于联调通过。`.test-build`、测试状态和包含会话令牌的运行数据不属于源码交付内容。

详细接口与存储说明见 `docs/SHOP-SUPPORT-CONTRACT.md`、`docs/SHOP-RESOURCE-CONTRACT.md` 和 `docs/SHOP-BACKEND-IMPLEMENTATION.md`。

## 与后续手机版原包的关系

原包是 `app.jar`、编译后的 `dist`、资料、SQL 导出和部署文档，未提供 `.vue`、`.java` 或 source map。本次依据其可核对的功能重建可维护实现，没有执行或重新发布原 JAR，也没有复制 SQL 中的用户、凭据、联系方式或支付配置。

参考能力包括原 `Manual` 的目录、分页与下载，`UpdateLog` 的时间线，`Chat` 与 `UnitChat` 的媒体消息，`Message` 与 `FeedbackAdmin` 的多附件、等级与软删除。餐饮项目使用自己的三份指南；没有把原电力设备说明书、商户品牌和备案信息混入当前产品。

原包的客户端自报身份、固定客服 ID、上传契约不一致、覆盖式单条回复和假阳性连接测试没有沿用。v7 保留已有额度与售后规则，并把新客服、资料和配置的功能范围及未接入项明确呈现。
