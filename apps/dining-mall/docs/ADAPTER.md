# 三端 UI 与 API 适配说明

本次保留通用购物商城的 Vue 3 / Element Plus 管理页面，并按芋道商城官方页面的移动端布局与红橙视觉方向制作餐饮顾客端。这不是未修改的芋道原版运行环境。

## 入口
- 顾客端：/#/mall/home
- 商家端：/#/merchant/home
- 平台端：/#/platform/home
- 选择身份：/#/login，三角色都是111/111，属于体验入口，不是真实身份认证

## API边界
页面调用集中在 src/utils/request.js。保留通用商城的请求形状（字符串code、data.records/total），当前由本机localStorage适配。原SQL账号、密码、电话、地址和交易记录未纳入发布。

| 页面能力 | 通用商城接口形状 | 本次状态 |
| --- | --- | --- |
| 商品/分类/店铺 | /goods/front/page、/goods/:id、/type、/unit/:id | 本机示例数据 |
| 购物车 | /cart、/cart/num、/cart/del/batch | 本机持久化 |
| 下单/订单 | /orders、/orders/fromCart/:addressId、/orders/front/page | 仅示例订单 |
| 管理页面CRUD | /entity/page、POST /entity、DELETE /entity/:id | 本机持久化 |
| 商家履约 | /orders/send/:id | 更新示例状态，不实际发货 |
| 先吃后付额度/账单/还款 | 原合同状态规则的本机适配层 | 顾客/商家/平台同源状态联动，尚未连接真实服务端 |
| 登录 | /web/login | 固定111/111体验身份，不签发token |
| 支付 | /orders/pay/:id | 明确拒绝，不伪造支付成功 |

通用商城API不能直接当作既有先吃后付96个接口。后续接入需要实际后端OpenAPI/路由、可用地址与跨域配置，在适配层完成字段映射；不必再次重画三端。

本机数据不跨设备同步，菜单按体验角色区分，但不构成安全权限边界。部署访问控制由具体托管平台负责，本源码包不携带托管绑定。

## 来源
- 用户提供的通用购物商城 Vue 3 源码包（保留页面结构与业务请求形状；不发布原始数据库和用户资料）
- 芋道视觉参考：yudaocode/yudao-mall-uniapp，官方商城页面截图与移动端导航布局
- 餐品图片来源详见food-image-sources.json

## 先吃后付业务恢复（本机演示）

原业务来源为用户原 code.zip 中 `miniapp/contract/transitions.json`、`data_dictionary.json`、`openapi.yaml`，及 Java `CommerceModule`、`FinanceModule`、`SimulationSeed`。规则保持原隔离夹具：额度10000分、单笔2000分、30天、确认凭证300秒、预占900秒；fixtureOnly=true、formalApproval=false。新增20元明确示例套餐，不修改真实规则。

`credit-service.js`提供纯业务状态；`request.js`在同一次状态事务中保存订单、预占、应收、分配和业务记录，并对失败回滚。可用Web Locks时跨标签页串行化；每个标签页身份存于sessionStorage，业务数据共用localStorage。仍是演示级系统，不构成生产鉴权、签名验真或服务器数据库事务。

- 顾客用餐请求：UI新增适配流程，不冒称原接口。顾客不能代替商家发布
- 商家创建/发布：DRAFT→PUBLISHED；固定餐品金额与版本快照，没有预占或应收
- 顾客主动确认：PUBLISHED→CONFIRMED，HELD预占，凭证用途/一次性/到期/快照/商品版本/额度守卫
- 顾客生成核销凭证：FULFILL与CONFIRM分开，重发使旧码失效
- 本店商家核销：CONFIRMED→FULFILLED，HELD→CONSUMED，新增OPEN应收；预占转本金，总占用不变
- 核销前取消/过期：一次释放；核销后不能取消消债
- 顾客还款：创建PENDING，不立即释放额度
- 平台701模拟结果：校验业务号、商家、金额、币种、环境，按应还日期/应收号分配本金；按实际本金恢复额度
- 重放同事件或同业务号不重复核减，超收保留unallocated且不额外提额
- TIMEOUT保持未知/PENDING；关闭或失败后的迟到成功→EXCEPTION；401提交核对依据与还款版本、402独立复核后才分配
- 平台401申请、402独立复核规则；额度不能小于现有占用；旧订单保留期限等快照
- 账单异议：本人提交、501处理；待处理异议/逾期阻止新增确认，但不阻止历史查询和还款

三端入口：顾客`/#/mall/bill`与`/#/mall/deferredOrder?id=...`，商家`/#/merchant/credit`，平台`/#/platform/credit`。商家仅见本店预占交易、待收本金、已还分配，不返回顾客跨店账户总额/可用额度。首期保证金和平台结算NOT_APPLICABLE。

尚未连接真实Java或原96个服务端端点；未实现完整生产签名、风控、线下收款核实、报表导出等全部原后台模块；财务退款现有明确的本机演示流程，见下文，不应称为原96接口全部接通。

## 可复现验证

- `node tests/adapter.test.cjs`：普通商城数据层回归
- `node tests/routes.test.cjs`：角色路由分离
- `node tests/credit-contract.test.mjs`：原状态规则和守恒/幂等/权限/到期/差异分支
- `node tests/credit-integration.test.mjs`：四个隔离身份会话共用业务存储，串联三端主链路；负/零/非有限/分以下金额、发布后改价/下架/改版本/删除反例无业务变动
- `npm run build`：Vue生产构建

上述为逻辑、路由和编译检查，不等于浏览器全链路视觉实测。当前执行环境的本地浏览器预览不可达，未把该限制包装成浏览器验证通过。

规则参数范围与原FinanceModule一致：额度和单笔上限各不超过1000000分、期限1–365天、凭证30–600秒、预占60–3600秒。

## 评价与售后（2026-10-04 更新）

- `feedback-service.js` 统一本人已履约资格、1–5星与文字校验；每个订单餐品只评一次，已评内容不被退款状态覆盖。店铺评价按历史订单归属聚合，商品下架/删除不抹掉历史评价。
- 顾客从订单列表、普通订单详情、信用账单及已履约信用订单进入 `mall/orderService`；`PurchaseFeedback.vue` 提供真实表单提交，已评内容和商家回复可回看。
- `mall/afterSales` / `mall/afterSale?id=...` 提供申请列表、金额拆分和处理时间线；商家、平台各自的 `afterSales` 页面独立处理。
- 信用金额售后遵循原 FinanceModule：申请不改变账本；402独立审批按当前应收分拆本金冲减/现金应退，订单保持FULFILLED；仅本金冲减恢复额度，701模拟现金退回不能再次提额。原还款分配与未分配款保持不变。
- REQUESTED退款计入待审占用；拒绝恢复可申请容量；处理中UNKNOWN不可重试，明确失败由402核查后重试，重试/审批/回调有幂等记录。
- 商家意见是UI协作扩展，不冒充原财务审批权限。普通结算售后记录商家同意/拒绝与原因，尚未连接普通支付退款通道，不展示虚假的到账。
- 不含退货物流、换货、真实退款、全部原后台能力。

补充验证：`node tests/after-sales.test.mjs` 覆盖20元订单/8元已还/15元退款分拆、全未还/全已还、超收未分配、待审上限、拒绝、scope、UNKNOWN与重试幂等、评价持久化等6组。

实际组件点击测试（不等于浏览器视觉验收）：运行 `npm ci` 后，`npm run test:dom` 将真实Vue应用挂载到JSDOM，点击信用账单→评价提交→餐品/店铺展示、售后申请→商家意见→402审批→701模拟退回→顾客进度，校验商家回复编辑、手机容器路由、平台支付/AI入口。此测试不访问线上用户存储，不验证像素布局或外层ChatGPT登录。

## 平台后续开发分组

系统支付管理 `platform/paymentManagement` 下分微信、银行卡、支付宝三个独立接入概览页。状态均为待接入；没有敏感配置输入框，不发真实支付请求。AI管理系统 `platform/aiManagement` 根据用户后续手机版部署包中已核实的 AiConfig 页面，整理AI配置/连接测试和客服会话/消息两个子组；本站待接入，不配置密钥、不调用模型。其余后续包能力未合入此版本。

全站应用品牌已更换餐碗与时钟矢量标志：顾客各页/登录/旧前台注册/商家平台导航、浏览器favicon和Apple图标均使用新版。商品、商家头像及第三方品牌保持自身归属。
