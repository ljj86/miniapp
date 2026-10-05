<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import ContractExplorer from "./components/ContractExplorer.vue";
import NotificationControls from "./components/NotificationControls.vue";
import {
  buildApplicationCommand,
  applicationPermissions,
  hasResourceScope,
  requirePermission,
  repaymentComplete,
  hasPlatformControllerScope,
  providerResultCommand,
} from "./supplemental";
import {
  createClient,
  captureCommand,
  requestKey,
  safePath,
  parseMinor,
  money,
  redact,
  errorHint,
  ApiError,
} from "./api";
import { operations, labels } from "./contract";
import { shanghaiDate, shanghaiTime, shanghaiDateTime } from "./dates.js";
import "./style.css";
const api = createClient(),
  fixtures = ref(null),
  workspace = ref(null),
  sessions = reactive({}),
  persona = ref(""),
  page = ref("workflow"),
  stage = ref(0),
  busy = ref(false),
  loading = ref(false),
  health = ref("连接中"),
  problem = ref(null),
  retryCommand = ref(null),
  notice = ref(""),
  logs = ref([]),
  lastResult = ref(null),
  lastRefresh = ref(null);
const flow = reactive({
  application: null,
  merchantUid: "",
  store: null,
  product: null,
  order: null,
  confirmCredential: null,
  fulfillCredential: null,
  receivable: null,
  repayment: null,
});
const form = reactive({
  merchantName: "青禾食集 · 模拟商家",
  regionCode: "310101",
  materialSummary: "合成模拟资料，用于隔离环境流程验证，不含真实证件。",
  reviewReason: "独立复核合成测试材料，允许继续模拟流程。",
  storeName: "青禾食集 · 测试一店",
  addressText: "模拟路 01 号（非真实地址）",
  productName: "双人午间套餐 · 模拟",
  priceMinor: "2000",
  quantity: 1,
  activationAccepted: false,
  confirmAccepted: false,
  credential: "",
  repayMinor: "",
  result: "PAYMENT_CONFIRMED",
  failureReason: "测试异常结果分支",
  callbackMode: "immediate",
});
const applicationHistory = ref(null);
const draftUpdateFields = ref([]),
  heldProviderEvent = ref(null);
const supplemental = reactive({
  merchantUid: "",
  businessDate: shanghaiDate(),
  differenceId: "",
  repaymentId: "",
  lateRequestId: "",
  version: 1,
  reason: "独立复核迟到入账的测试证据",
});
const stages = [
  ["01", "商家入驻", "申请与独立复核"],
  ["02", "门店与套餐", "建立模拟商品"],
  ["03", "订单确认", "发布与逐笔确认"],
  ["04", "核销履约", "凭证换模拟应收"],
  ["05", "还款与结果", "验证入账与余额"],
];
const resolvedBy = ref("");
const selectedPersona = computed(() =>
    fixtures.value?.users.find((u) => u.phone === persona.value),
  ),
  session = computed(() => sessions[persona.value]),
  resources = computed(() => workspace.value?.resources || {}),
  roles = computed(
    () => workspace.value?.roleCodes || [],
  ),
  account = computed(() => workspace.value?.account),
  isConsumer = computed(() => roles.value.includes("USER") && !roles.value.some(role => ["OWNER", "CLERK", "MERCHANT_REVIEWER", "SIM_CONTROLLER", "LEDGER_MAKER", "LEDGER_CHECKER", "SECURITY", "SUPPORT"].includes(role))),
  isMerchant = computed(
    () => roles.value.includes("OWNER") || roles.value.includes("CLERK"),
  ),
  isReviewer = computed(() => roles.value.includes("MERCHANT_REVIEWER")),
  isController = computed(() => roles.value.includes("SIM_CONTROLLER"));
const applicationAccess = computed(() => applicationPermissions(workspace.value, flow.application));
const canEditApplication = computed(() => applicationAccess.value.edit);
const canReviewApplication = computed(() => applicationAccess.value.review);
const canCreateStore = computed(() => !!flow.merchantUid && hasResourceScope(workspace.value?.scopeGrants, ['OWNER'], { merchantUid: flow.merchantUid }));
const canCreateProduct = computed(() => !!flow.store && hasResourceScope(workspace.value?.scopeGrants, ['OWNER'], { merchantUid: flow.store.merchantUid, storeId: flow.store.id }));
const canCreateOrder = computed(() => !!flow.store && hasResourceScope(workspace.value?.scopeGrants, ['OWNER', 'CLERK'], { merchantUid: flow.store.merchantUid, storeId: flow.store.id }));
const canManageOrder = computed(() => !!flow.order && hasResourceScope(workspace.value?.scopeGrants, ['OWNER', 'CLERK'], flow.order));
const canControlRepayment = computed(() => !!flow.repayment && hasResourceScope(workspace.value?.scopeGrants, ['SIM_CONTROLLER'], flow.repayment));
const ownsOrder = computed(() => !!flow.order?.userUid && flow.order.userUid === workspace.value?.user?.uid);
const ownsReceivable = computed(() => !!flow.receivable?.userUid && flow.receivable.userUid === workspace.value?.user?.uid);
const isPlatformController = computed(() =>
  hasPlatformControllerScope(workspace.value?.scopeGrants),
);
const stageComplete = computed(() => [
  flow.application?.status === "APPROVED",
  !!flow.product,
  flow.order && ["CONFIRMED", "FULFILLED"].includes(flow.order.status),
  !!flow.receivable,
  repaymentComplete(flow.repayment),
]);
const context = computed(() => ({
  merchantUid: flow.merchantUid || flow.order?.merchantUid || "",
  storeId: flow.store?.id || "",
  orderId: flow.order?.id || "",
  receivableId: flow.receivable?.id || "",
  userUid: flow.order?.userUid || "",
  bookId: resources.value.books?.[0]?.id || "",
  version: flow.order?.version || 1,
  reference: flow.repayment?.reference || "",
  amountMinor: flow.receivable?.outstandingMinor || 1,
  documentVersion: fixtures.value?.agreementVersion || "",
  agreementVersion: fixtures.value?.agreementVersion || "",
  contractVersion: fixtures.value?.agreementVersion || "",
  privacyVersion: fixtures.value?.privacyVersion || "",
  environment: "SIMULATION",
  currency: "CNY",
}));
const resourceTypes = [
  ["orders", "订单"],
  ["receivables", "模拟应收"],
  ["repayments", "模拟还款"],
  ["journals", "账务凭证"],
  ["applications", "入驻申请"],
  ["merchants", "商家"],
  ["stores", "门店"],
  ["products", "套餐"],
  ["refunds", "退款"],
  ["disputes", "异议"],
  ["adjustments", "调账"],
  ["rules", "规则"],
  ["reconciliations", "对账任务"],
  ["differences", "对账差异"],
  ["books", "账簿"],
  ["businessDays", "营业日"],
  ["dayRequests", "日结审批"],
  ["exports", "导出"],
  ["files", "材料"],
  ["notifications", "通知"],
  ["notificationOutbox", "通知投递"],
  ["scopeGrants", "角色授权"],
];
const resourceType = ref("orders"),
  recordDetail = ref(null),
  filter = ref("");
const rows = computed(() =>
  (resourceType.value === "scopeGrants"
    ? workspace.value?.scopeGrants || []
    : resources.value[resourceType.value] || []
  ).filter(
    (r) =>
      !filter.value ||
      JSON.stringify(r).toLowerCase().includes(filter.value.toLowerCase()),
  ),
);
const columns = computed(() => {
  const preferred =
    resourceType.value === "notificationOutbox"
      ? [
          "id",
          "status",
          "attemptCount",
          "cycleAttemptCount",
          "cycle",
          "nextAttemptAt",
          "version",
        ]
      : [
          "id",
          "name",
          "merchantUid",
          "orderId",
          "status",
          "totalMinor",
          "amountMinor",
          "outstandingMinor",
          "version",
        ];
  const keys = new Set(rows.value.flatMap((r) => Object.keys(r)));
  return preferred.filter((k) => keys.has(k)).slice(0, 7);
});
const valueText = (k, v) =>
  v === undefined || v === null
    ? "—"
    : (k.endsWith("At") || ['validFrom', 'validTo'].includes(k))
      ? shanghaiDateTime(v)
      : k.endsWith("Minor")
      ? money(v)
      : typeof v === "boolean"
        ? v
          ? "是"
          : "否"
        : typeof v === "object"
          ? JSON.stringify(v)
          : String(v);
const statusText = (s) =>
  ({
    DRAFT: "草稿",
    SUBMITTED: "待复核",
    NEEDS_INFO: "待补件",
    RETRY_WAIT: "待自动重试",
    DEAD: "死信",
    DELIVERED: "已投递",
    PENDING: "待处理",
    APPROVED: "已批准（模拟）",
    PUBLISHED: "待确认",
    CONFIRMED: "已确认",
    FULFILLED: "已履约",
    PROCESSING: "处理中",
    SUCCEEDED: "成功",
    OPEN: "未结清",
    SETTLED: "已结清",
    EXPIRED: "已过期",
    FAILED: "失败",
    REJECTED: "已拒绝",
    CANCELLED: "已取消",
    TIMEOUT: "超时",
    MATCHED: "已匹配",
    DIFFERENCE: "有差异",
    CLOSED: "已关闭",
    ACTIVE: "有效",
  })[s] ||
  s ||
  "待创建";
function log(title, ok, result) {
  logs.value.unshift({
    title,
    ok,
    persona: selectedPersona.value?.label || "未登录",
    at: shanghaiTime(),
    detail: redact(result),
  });
  logs.value = logs.value.slice(0, 40);
}
function syncFlow(data) {
  for (const [field, key] of [
    ["application", "applications"],
    ["store", "stores"],
    ["product", "products"],
    ["order", "orders"],
    ["receivable", "receivables"],
    ["repayment", "repayments"],
  ]) {
    const found = data.resources?.[key]?.find((r) => r.id === flow[field]?.id);
    if (found) flow[field] = found;
  }
  if (flow.application?.merchantUid)
    flow.merchantUid = flow.application.merchantUid;
}
async function refresh({ quiet = false } = {}) {
  if (loading.value) return;
  loading.value = true;
  const phone = persona.value,
    token = sessions[phone]?.accessToken;
  try {
    const fixtureResult = await api.request("/api/v1/simulation-fixtures");
    fixtures.value = fixtureResult.data;
    health.value = "后端已连接";
    if (!persona.value) persona.value = fixtures.value.users[0]?.phone || "";
    if (token) {
      const r = await api.request("/api/v1/simulation-workspace", { token });
      if (phone === persona.value) {
        workspace.value = r.data;
        syncFlow(r.data);
        lastRefresh.value = shanghaiTime();
      }
    }
    if (!quiet) notice.value = "已读取后端最新状态";
  } catch (e) {
    if (e.status === 401) {
      delete sessions[phone];
      if (phone === persona.value) workspace.value = null;
    }
    health.value = e.retryable ? "连接中断" : "服务可达";
    if (!quiet) problem.value = e;
    else notice.value = `操作已结束，但刷新失败：${e.message}`;
  } finally {
    loading.value = false;
  }
}
async function choosePersona(phone) {
  if (busy.value || loading.value) return;
  persona.value = phone;
  workspace.value = null;
  recordDetail.value = null;
  lastResult.value = null;
  problem.value = null;
  notice.value = "";
  if (sessions[phone]) await refresh({ quiet: true });
}
async function login() {
  if (busy.value) return;
  busy.value = true;
  problem.value = null;
  const phone = persona.value;
  try {
    const challenge = (
      await api.request("/api/v1/auth/sms-challenges", {
        method: "POST",
        body: { phone, purpose: "LOGIN" },
        key: requestKey(),
      })
    ).data;
    const result = (
      await api.request("/api/v1/auth/sessions", {
        method: "POST",
        body: {
          provider: "PHONE_OTP",
          phone,
          challengeId: challenge.challengeId,
          code: fixtures.value.mockCode,
        },
        key: requestKey(),
      })
    ).data;
    sessions[phone] = result;
    if (retryCommand.value?.phone === phone)
      retryCommand.value.token = result.accessToken;
    log("测试身份登录", true, { uid: result.user?.uid });
    notice.value = "已建立当前测试身份的内存会话";
    await refresh({ quiet: true });
  } catch (e) {
    problem.value = e;
    log("测试身份登录", false, { code: e.code, message: e.message });
  } finally {
    busy.value = false;
  }
}
async function execute(command) {
  if (busy.value) return;
  if (
    retryCommand.value &&
    command.method !== "GET" &&
    command.key !== retryCommand.value.key
  ) {
    problem.value = new ApiError(
      "上一笔写入结果尚未确认，请先切回原身份并使用原请求重试。",
      { code: "PENDING_RESULT" },
    );
    return;
  }
  busy.value = true;
  problem.value = null;
  notice.value = "";
  const captured = captureCommand(command, { phone: persona.value, token: session.value?.accessToken });
  try {
    const result = await api.once(captured.key, captured.path, {
      method: captured.method || "POST",
      body: captured.body,
      token: captured.token,
      key: captured.key,
      query: captured.query,
    });
    if (retryCommand.value?.key === captured.key) retryCommand.value = null;
    lastResult.value = {
      title: captured.title,
      data: redact(result.data),
      requestId: result.meta.requestId,
    };
    captured.apply?.(result.data);
    log(captured.title, true, {
      ...redact(result.data),
      requestId: result.meta.requestId,
    });
    notice.value = `${captured.title}：后端已确认`;
    await refresh({ quiet: true });
    return result.data;
  } catch (e) {
    problem.value = e;
    if (e.status === 401) {
      delete sessions[captured.phone];
      if (persona.value === captured.phone) workspace.value = null;
    }
    if ((captured.method || "POST") !== "GET") {
      if (
        e.retryable ||
        (e.status === 401 && retryCommand.value?.key === captured.key)
      )
        retryCommand.value = captured;
      else if (retryCommand.value?.key === captured.key)
        retryCommand.value = null;
    }
    log(captured.title, false, {
      code: e.code,
      message: e.message,
      requestId: e.requestId,
    });
  } finally {
    busy.value = false;
  }
}
function command(title, path, body, apply, method = "POST") {
  return execute({ title, path, body, apply, method });
}
function need(value, message) {
  if (!value) throw new ApiError(message, { code: "INPUT_REQUIRED" });
  return value;
}
async function action(fn) {
  try {
    await fn();
  } catch (e) {
    problem.value = e;
  }
}
function guardedAction(allowed, fn) {
  return action(() => { requirePermission(allowed); return fn(); });
}
function merchantApply() {
  return guardedAction(applicationAccess.value.create,() =>
    command(
      "提交模拟入驻申请",
      "/api/v1/merchant-applications",
      {
        merchantName: form.merchantName,
        regionCode: form.regionCode,
        contractVersion: fixtures.value.agreementVersion,
        materialSummary: form.materialSummary,
      },
      (d) => (flow.application = d),
    ),
  );
}
function applicationLifecycle(kind) {
  return action(() => {
    const payload = {
      merchantName: form.merchantName,
      regionCode: form.regionCode,
      contractVersion: fixtures.value.agreementVersion,
      materialSummary: form.materialSummary,
      reason: form.reviewReason,
    };
    requirePermission(applicationAccess.value[kind === "requestInformation" ? "review" : kind === "update" ? "edit" : kind === "submit" ? "submit" : "create"]);
    const spec = buildApplicationCommand(
      kind,
      flow.application,
      payload,
      draftUpdateFields.value,
    );
    return execute({
      ...spec,
      apply: (d) => {
        flow.application = d;
        draftUpdateFields.value = [];
      },
    });
  });
}
function loadApplicationHistory() {
  return guardedAction((resources.value.applications || []).some(row => row.id === flow.application?.id), () =>
    command('读取已提交材料与审批历史', safePath('/api/v1/merchant-applications/{id}/history', { id: flow.application.id }), undefined, data => { applicationHistory.value = data; }, 'GET'));
}
function merchantReview(decision) {
  return guardedAction(canReviewApplication.value,() =>
    command(
      decision === "APPROVE" ? "独立复核通过" : "独立复核拒绝",
      `/api/v1/admin/merchant-applications/${need(flow.application?.id, "请先创建或选择申请")}/decision`,
      {
        version: flow.application.version,
        decision,
        reason: form.reviewReason,
        evidenceIds: [],
      },
      (d) => (flow.application = d),
    ),
  );
}
function storeCreate() {
  return guardedAction(canCreateStore.value,() =>
    command(
      "创建模拟门店",
      "/api/v1/stores",
      {
        merchantUid: need(flow.merchantUid, "请先选择已批准的商家 UID"),
        name: form.storeName,
        regionCode: form.regionCode,
        addressText: form.addressText,
      },
      (d) => (flow.store = d),
    ),
  );
}
function productCreate() {
  return guardedAction(canCreateProduct.value,() =>
    command(
      "创建模拟套餐",
      "/api/v1/products",
      {
        storeId: need(flow.store?.id, "请先选择门店"),
        name: form.productName,
        priceMinor: parseMinor(form.priceMinor),
      },
      (d) => (flow.product = d),
    ),
  );
}
function orderCreate() {
  return guardedAction(canCreateOrder.value,() =>
    command(
      "创建订单草稿",
      "/api/v1/orders",
      {
        storeId: need(flow.store?.id, "请先选择门店"),
        items: [
          {
            productId: need(flow.product?.id, "请先选择套餐"),
            quantity: parseMinor(form.quantity),
            productVersion: flow.product.version,
          },
        ],
        clientReference: requestKey(),
      },
      (d) => {
        flow.order = d;
        resolvedBy.value = "";
        flow.confirmCredential = null;
        flow.fulfillCredential = null;
        flow.receivable = null;
        flow.repayment = null;
        form.confirmAccepted = false;
      },
    ),
  );
}
function publish() {
  return guardedAction(canManageOrder.value && flow.order?.status === "DRAFT",() =>
    command(
      "发布订单确认凭证",
      `/api/v1/orders/${need(flow.order?.id, "请先创建订单")}/publish`,
      { version: flow.order.version },
      (d) => {
        flow.confirmCredential = d;
        form.credential = d.credential;
      },
    ),
  );
}
function activate() {
  return guardedAction(roles.value.includes("USER"),() => {
    need(form.activationAccepted, "请阅读并勾选模拟告知");
    return command("开通模拟资格", "/api/v1/me/simulation-activation", {
      purpose: "SIMULATION",
      documentVersion: fixtures.value.agreementVersion,
      accepted: true,
    });
  });
}
function resolveOrder() {
  return guardedAction(roles.value.includes("USER"),() =>
    command(
      "读取待确认订单快照",
      "/api/v1/order-credentials/resolve",
      { credential: need(form.credential, "请输入确认凭证") },
      (d) => {
        flow.order = d;
        resolvedBy.value = persona.value;
        form.confirmAccepted = false;
      },
    ),
  );
}
function confirm() {
  return guardedAction(roles.value.includes("USER") && flow.order?.status === "PUBLISHED",() => {
    need(
      resolvedBy.value === persona.value,
      "请先以当前消费者身份读取订单快照",
    );
    need(form.confirmAccepted, "请先逐笔确认已阅读金额与模拟协议");
    return command(
      "消费者逐笔确认",
      `/api/v1/orders/${need(flow.order?.id, "请先读取订单快照")}/confirm`,
      {
        version: flow.order.version,
        credential: form.credential,
        snapshotHash: flow.order.snapshotHash,
        agreementVersion: fixtures.value.agreementVersion,
        confirmed: true,
      },
      (d) => (flow.order = d.order),
    );
  });
}
function fulfillmentCredential() {
  return guardedAction(ownsOrder.value && flow.order?.status === "CONFIRMED",() =>
    command(
      "取得专用核销凭证",
      `/api/v1/orders/${need(flow.order?.id, "请先确认订单")}/fulfillment-credential`,
      { version: flow.order.version },
      (d) => (flow.fulfillCredential = d),
    ),
  );
}
function fulfill() {
  return guardedAction(canManageOrder.value && flow.order?.status === "CONFIRMED",() =>
    command(
      "商家核销履约",
      `/api/v1/orders/${need(flow.order?.id, "请选择订单")}/fulfill`,
      {
        version: flow.order.version,
        credential: need(
          flow.fulfillCredential?.credential,
          "请由消费者先取得核销凭证",
        ),
      },
      (d) => {
        flow.receivable = d;
        form.repayMinor = String(d.outstandingMinor);
      },
    ),
  );
}
function repay() {
  return guardedAction(ownsReceivable.value && flow.receivable.outstandingMinor > 0,() =>
    command(
      "提交模拟还款",
      "/api/v1/repayments",
      {
        merchantUid: need(flow.receivable?.merchantUid, "请选择应收记录"),
        receivableIds: [flow.receivable.id],
        amountMinor: parseMinor(form.repayMinor),
        method: "MOCK_ONLINE",
      },
      (d) => (flow.repayment = d),
    ),
  );
}
function resultControl() {
  return action(() => {
    requirePermission(canControlRepayment.value);
    const r = need(flow.repayment, "请先创建模拟还款");
    if (form.result !== "PAYMENT_CONFIRMED")
      return command(
        "注入模拟异常结果",
        "/api/v1/simulation-controls/failures",
        {
          reference: r.reference,
          version: r.version,
          result: form.result,
          reason: form.failureReason,
          eventId: requestKey(),
        },
      );
    const result = {
      providerEventId: requestKey(),
      reference: r.reference,
      eventType: "PAYMENT_CONFIRMED",
      amountMinor: r.amountMinor,
      merchantUid: r.merchantUid,
      currency: "CNY",
      occurredAt: new Date().toISOString(),
      environment: "SIMULATION",
    };
    if (form.callbackMode === "immediate")
      return command("注入模拟成功事件", "/api/v1/simulator/results", result);
    const deliverCallback = form.callbackMode === "provider-deliver";
    return execute({
      ...providerResultCommand(result, deliverCallback),
      apply: () => {
        if (!deliverCallback) heldProviderEvent.value = { event: JSON.parse(JSON.stringify(result)), phone: persona.value };
      },
    });
  });
}
function deliverHeldCallback() {
  return guardedAction(heldProviderEvent.value?.phone === persona.value && hasResourceScope(workspace.value?.scopeGrants, ["SIM_CONTROLLER"], { merchantUid: heldProviderEvent.value?.event?.merchantUid }),() =>
    execute({
      ...providerResultCommand(
        need(heldProviderEvent.value?.event, "本页没有暂缓的渠道结果"),
        true,
      ),
      apply: () => {
        heldProviderEvent.value = null;
      },
    }),
  );
}
function cancelOrder() {
  return guardedAction(canManageOrder.value && ["DRAFT", "PUBLISHED", "CONFIRMED"].includes(flow.order?.status),() =>
    command(
      "取消未履约订单",
      `/api/v1/orders/${need(flow.order?.id, "请选择订单")}/cancel`,
      { version: flow.order.version, reason: "模拟工作台主动取消" },
      (d) => (flow.order = d),
    ),
  );
}
function setSelection(kind, id) {
  const r = resources.value[kind]?.find((r) => r.id === id);
  if (!r) return;
  if (kind === "applications") {
    flow.application = r;
    flow.merchantUid = r.merchantUid || "";
    draftUpdateFields.value = [];
    applicationHistory.value = null;
    for (const field of ["merchantName", "regionCode", "materialSummary"]) form[field] = r.materials?.[field] || "";
    const merchant = (resources.value.merchants || []).find(
      (m) => m.id === r.merchantId,
    );
    if (merchant?.name) form.merchantName = merchant.name;
  }
  if (kind === "stores") {
    flow.store = r;
    flow.merchantUid = r.merchantUid;
    flow.product = null;
  }
  if (kind === "products") flow.product = r;
  if (kind === "orders") {
    flow.order = r;
    resolvedBy.value = "";
    flow.confirmCredential = null;
    flow.fulfillCredential = null;
    form.credential = "";
    form.confirmAccepted = false;
  }
  if (kind === "receivables") {
    flow.receivable = r;
    form.repayMinor = String(r.outstandingMinor);
  }
  if (kind === "repayments") flow.repayment = r;
}
async function contractExecute({ operation, body, params, query }) {
  await action(() =>
    execute({
      title: operation.title,
      path: safePath(operation.path, params),
      method: operation.method,
      body,
      query,
    }),
  );
}
async function logout() {
  await command(
    "退出当前会话",
    "/api/v1/auth/sessions/current",
    undefined,
    () => {
      delete sessions[persona.value];
      workspace.value = null;
    },
    "DELETE",
  );
}
async function downloadExport(row) {
  await action(async () => {
    const result = await command(
      "申请已批准导出下载",
      `/api/v1/exports/${row.id}/download`,
      undefined,
      undefined,
      "GET",
    );
    const url = result?.url || result?.downloadUrl;
    if (!url) return;
    const parsed = new URL(url, location.origin);
    if (
      !["http://127.0.0.1:48080", location.origin].includes(parsed.origin) ||
      parsed.pathname !==
        `/api/v1/exports/${encodeURIComponent(row.id)}/content`
    )
      throw new ApiError("下载目标必须为指定的模拟导出资源", {
        code: "INVALID_DESTINATION",
      });
    const { data } = await api.request(parsed.pathname + parsed.search, {
      token: session.value?.accessToken,
      binary: true,
    });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(data);
    link.download = `simulation-export-${row.id}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  });
}
async function channelBill() {
  return action(async () => {
    need(session.value, "请先登录有权限的测试身份");
    need(supplemental.merchantUid || flow.merchantUid, "请填写商家 UID");
    busy.value = true;
    problem.value = null;
    try {
      const { data } = await api.request(
        "/api/v1/admin/simulator/channel-bill",
        {
          token: session.value.accessToken,
          query: {
            merchantUid: supplemental.merchantUid || flow.merchantUid,
            businessDate: supplemental.businessDate,
          },
          binary: true,
        },
      );
      const a = document.createElement("a");
      a.href = URL.createObjectURL(data);
      a.download = `simulation-channel-${supplemental.businessDate}.csv`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 1000);
      notice.value = "模拟渠道对账单已下载，可作为 RECON_IMPORT 材料上传";
      log("下载模拟渠道对账单", true, {
        businessDate: supplemental.businessDate,
      });
    } catch (e) {
      problem.value = e;
    } finally {
      busy.value = false;
    }
  });
}
function lateAllocation() {
  return action(() =>
    command(
      "申请迟到入账分配",
      "/api/v1/admin/late-allocations",
      {
        differenceId: need(supplemental.differenceId, "请输入差异 ID"),
        repaymentId: need(supplemental.repaymentId, "请输入还款 ID"),
        evidenceIds: [],
      },
      (d) => {
        supplemental.lateRequestId = d.id;
        supplemental.version = d.version;
      },
    ),
  );
}
function lateDecision(decision) {
  return action(() =>
    command(
      "独立复核迟到分配",
      `/api/v1/admin/late-allocations/${encodeURIComponent(need(supplemental.lateRequestId, "请输入迟到分配申请 ID"))}/decision`,
      {
        version: parseMinor(supplemental.version),
        decision,
        reason: supplemental.reason,
        evidenceIds: [],
      },
    ),
  );
}
onMounted(() => refresh({ quiet: true }));
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="page = 'workflow'"
        ><span class="brand-mark">禾</span
        ><span>青禾模拟台<small>MINIAPP · SANDBOX</small></span></a
      >
      <div class="workspace-label">隔离工作空间 <span>DEV</span></div>
      <nav aria-label="主导航">
        <button
          :class="{ active: page === 'workflow' }"
          @click="page = 'workflow'"
        >
          <span>◈</span> 业务工作流</button
        ><button :class="{ active: page === 'data' }" @click="page = 'data'">
          <span>▤</span> 数据与账务</button
        ><button
          :class="{ active: page === 'contract' }"
          @click="page = 'contract'"
        >
          <span>⌘</span> 高级操作</button
        ><button
          :class="{ active: page === 'activity' }"
          @click="page = 'activity'"
        >
          <span>◷</span> 请求记录 <small>{{ logs.length }}</small>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <span
          class="status-light"
          :class="{ online: health === '后端已连接' }"
        ></span
        >{{ health }}
        <p>统一 Spring Boot API<br />仅合成数据 · 不产生真实资金</p>
        <span class="version-tag">模拟验证 0.1–1.0</span>
      </div>
    </aside>
    <main>
      <nav class="mobile-nav" aria-label="移动导航">
        <button
          @click="page = 'workflow'"
          :class="{ active: page === 'workflow' }"
        >
          工作流</button
        ><button @click="page = 'data'" :class="{ active: page === 'data' }">
          数据账务</button
        ><button
          @click="page = 'contract'"
          :class="{ active: page === 'contract' }"
        >
          高级操作</button
        ><button
          @click="page = 'activity'"
          :class="{ active: page === 'activity' }"
        >
          请求记录
        </button>
      </nav>
      <header class="topbar">
        <div>
          <span class="breadcrumb"
            >工作空间 /
            {{
              page === "workflow"
                ? "业务工作流"
                : page === "data"
                  ? "数据与账务"
                  : page === "contract"
                    ? "高级操作"
                    : "请求记录"
            }}</span
          >
          <h1>
            {{
              page === "workflow"
                ? "让每一步，都有据可查"
                : page === "data"
                  ? "查看当前身份的真实返回"
                  : page === "contract"
                    ? "覆盖完整契约的模拟操作"
                    : "回看本页操作记录"
            }}
          </h1>
        </div>
        <div class="top-actions">
          <span class="simulation-badge">SIMULATION ONLY</span
          ><button
            class="secondary small"
            @click="refresh()"
            :disabled="busy || loading"
          >
            {{ loading ? "同步中…" : "↻ 刷新状态" }}
          </button>
        </div>
      </header>
      <div class="safety-banner">
        <span>ⓘ</span>
        <div>
          <b>这是隔离模拟环境</b> ·
          仅测试身份与合成资料，不产生真实债务、收付款或可提现权益。入驻与复核只生成测试记录。
        </div>
      </div>
      <section class="persona-bar panel">
        <div class="persona-icon">
          {{ selectedPersona?.label?.includes("消费者") ? "客" : "角" }}
        </div>
        <div class="persona-select">
          <label for="persona">当前操作身份</label
          ><select
            id="persona"
            :value="persona"
            @change="choosePersona($event.target.value)"
            :disabled="busy || loading"
          >
            <option v-if="!fixtures" value="">等待测试身份加载</option>
            <option
              v-for="u in fixtures?.users || []"
              :key="u.phone"
              :value="u.phone"
            >
              {{ u.label }} · {{ u.uid }}
            </option>
          </select>
        </div>
        <div class="persona-description">
          <span class="pill" :class="{ positive: session }">{{
            session ? "已登录" : "未登录"
          }}</span
          ><small>{{ roles.join(" / ") || "Mock 身份专用" }}</small>
        </div>
        <button
          v-if="!session"
          :disabled="busy || loading || !persona"
          @click="login"
        >
          {{ busy ? "建立会话…" : "登录此测试身份" }}</button
        ><button v-else class="text-button" :disabled="busy" @click="logout">
          退出
        </button>
      </section>
      <p class="session-note">
        测试验证码由后端夹具提供，无短信费用。各身份会话只在本页内存保存，刷新页面需重新登录。切换身份用于验证独立复核，不代表真实微信登录。
      </p>
      <div v-if="retryCommand" class="feedback error" role="status">
        <div><b>上一笔写入结果尚未确认</b><p>请切回原身份后重试同一请求。刷新和关闭提示不会清除该请求。</p></div>
        <button class="danger small" :disabled="busy || retryCommand.phone !== persona || !session" @click="execute(retryCommand)">原请求重试</button>
      </div>
      <div v-if="problem" class="feedback error" role="alert">
        <div>
          <b>{{ problem.message }}</b>
          <p>{{ errorHint(problem) }}</p>
          <small
            >{{ problem.code
            }}{{
              problem.requestId ? ` · 请求 ${problem.requestId}` : ""
            }}</small
          >
        </div>
        <div class="actions">
          <button
            class="secondary small"
            @click="refresh()"
            :disabled="busy || loading"
          >
            刷新记录</button
          ><button
            class="text-button"
            @click="problem = null"
            aria-label="关闭错误提示"
          >
            ×
          </button>
        </div>
      </div>
      <div v-else-if="notice" class="feedback success" role="status">
        {{ notice
        }}<button
          class="text-button"
          @click="notice = ''"
          aria-label="关闭提示"
        >
          ×
        </button>
      </div>
      <template v-if="page === 'workflow'">
        <section class="metrics">
          <article>
            <span>当前可用模拟额度</span
            ><strong>{{ money(account?.availableMinor) }}</strong
            ><small>{{
              account
                ? `总额 ${money(account.totalMinor)}`
                : "消费者开通模拟资格后读取"
            }}</small>
          </article>
          <article>
            <span>模拟预占 / 未结本金</span
            ><strong class="compact"
              >{{ money(account?.reservedMinor) }} <i>/</i>
              {{ money(account?.principalMinor) }}</strong
            ><small>{{
              account?.blocked
                ? "新增模拟消费已冻结"
                : "余额以当前身份服务端返回为准"
            }}</small>
          </article>
          <article>
            <span>当前工作流订单</span
            ><strong class="compact">{{
              statusText(flow.order?.status)
            }}</strong
            ><small>{{
              flow.order
                ? `#${flow.order.id} · ${money(flow.order.totalMinor)}`
                : "从商家入驻开始，或选择已有记录"
            }}</small>
          </article>
        </section>
        <div class="section-heading">
          <div>
            <h2>走通一笔模拟交易</h2>
            <p>按角色逐步操作，从入驻到核销、还款和账务核对</p>
          </div>
          <span class="muted"
            >{{ stageComplete.filter(Boolean).length }} / 5 个阶段已有结果</span
          >
        </div>
        <div class="steps">
          <button
            v-for="(s, i) in stages"
            :key="s[0]"
            :class="{ current: stage === i, complete: stageComplete[i] }"
            @click="stage = i"
          >
            <span class="step-number">{{ stageComplete[i] ? "✓" : s[0] }}</span
            ><span
              ><b>{{ s[1] }}</b
              ><small>{{ s[2] }}</small></span
            >
          </button>
        </div>
        <div v-if="!session" class="empty-state panel">
          <div class="empty-symbol">◈</div>
          <h2>选择一个测试身份，开始验证</h2>
          <p>
            首次演示建议选择「模拟商家申请人」，登录后创建入驻申请。<br />之后切换独立复核人、消费者和结果控制员，分别执行各自步骤。
          </p>
        </div>
        <section v-else class="workflow-layout">
          <article class="panel workflow-panel">
            <template v-if="stage === 0"
              ><div class="section-top">
                <div>
                  <span class="eyebrow">STEP 01 · MERCHANT</span>
                  <h2>商家入驻与独立复核</h2>
                </div>
                <span class="pill">{{
                  statusText(flow.application?.status)
                }}</span>
              </div>
              <p>
                申请人可先保存草稿，提交时冻结材料版本；待补件后须先保存新材料，再重新提交。复核人独立作出模拟决定。
              </p>
              <div v-if="flow.application" class="notice">
                申请 #{{ flow.application.id }} · 对象版本
                {{ flow.application.version }} · 材料版本
                {{ flow.application.materialVersion
                }}<br v-if="flow.application.reviewReason" /><span
                  v-if="flow.application.reviewReason"
                  >复核意见：{{ flow.application.reviewReason }}</span
                >
              </div>
              <div v-if="resources.applications?.length" class="field">
                <label>继续已有申请</label
                ><select
                  :value="flow.application?.id || ''"
                  @change="setSelection('applications', $event.target.value)"
                >
                  <option value="">选择申请</option>
                  <option
                    v-for="a in resources.applications"
                    :key="a.id"
                    :value="a.id"
                  >
                    #{{ a.id }} · {{ statusText(a.status) }} ·
                    {{ a.merchantUid }}
                  </option>
                </select>
              </div>
              <details v-if="flow.application?.materials" class="draft-revision">
                <summary>服务端保存的材料{{ isReviewer ? '（已提交快照）' : '' }}</summary>
                <dl><dt>商家名称</dt><dd>{{ flow.application.materials.merchantName || '未填写' }}</dd><dt>地区</dt><dd>{{ flow.application.materials.regionCode || '未填写' }}</dd><dt>协议版本</dt><dd>{{ flow.application.materials.contractVersion || '未填写' }}</dd><dt>合成材料摘要</dt><dd>{{ flow.application.materials.materialSummary || '未填写' }}</dd></dl>
              </details>
              <button v-if="flow.application" class="secondary small" type="button" :disabled="busy" @click="loadApplicationHistory">读取材料与审批历史</button>
              <details v-if="applicationHistory" class="draft-revision"><summary>服务端材料版本与审批记录</summary><pre>{{ JSON.stringify(redact(applicationHistory), null, 2) }}</pre></details>
              <form
                v-if="!isReviewer"
                @submit.prevent="
                  canEditApplication
                    ? applicationLifecycle('update')
                    : merchantApply()
                "
              >
                <div class="form-grid">
                  <label class="field full-width"
                    >模拟商家名称<input
                      v-model="form.merchantName"
                      required
                      maxlength="128" /></label
                  ><label class="field"
                    >地区码<input
                      v-model="form.regionCode"
                      required
                      maxlength="16" /></label
                  ><label class="field"
                    >参与协议版本<input
                      :value="fixtures.agreementVersion"
                      readonly /></label
                  ><label class="field full-width"
                    >合成材料摘要<textarea
                      v-model="form.materialSummary"
                      rows="3"
                      maxlength="1000"
                      required
                    />
                  </label>
                </div>
                <div class="actions">
                  <button v-if="!canEditApplication" :disabled="busy || !applicationAccess.create">
                    直接创建并提交</button
                  ><button
                    class="secondary"
                    type="button"
                    @click="applicationLifecycle('create')"
                    :disabled="busy || !applicationAccess.create"
                  >
                    另存新的模拟草稿
                  </button>
                </div>
                <div v-if="canEditApplication" class="draft-revision">
                  <h3>修改当前申请 #{{ flow.application.id }}</h3>
                  <p>
                    请在上方填写新内容，并明确选择本次更新字段；未选字段会保留服务端原值。材料版本与复核历史由服务端保存。
                  </p>
                  <div class="field-options">
                    <label
                      v-for="field in [
                        'merchantName',
                        'regionCode',
                        'contractVersion',
                        'materialSummary',
                      ]"
                      :key="field"
                      class="checkbox"
                      ><input
                        type="checkbox"
                        :value="field"
                        v-model="draftUpdateFields"
                      /><span>{{ labels[field] || field }}</span></label
                    >
                  </div>
                  <div class="actions">
                    <button
                      class="secondary"
                      type="button"
                      @click="applicationLifecycle('update')"
                      :disabled="busy || !draftUpdateFields.length"
                    >
                      保存选中材料的新版本</button
                    ><button
                      type="button"
                      @click="applicationLifecycle('submit')"
                      :disabled="busy || !applicationAccess.submit"
                    >
                      提交当前已保存版本
                    </button>
                  </div>
                  <p class="muted">
                    提交只使用服务器已保存的版本。待补件记录须先修改并保存材料；已拒绝申请不可修改，原复核证据保持不变。
                  </p>
                </div>
              </form>
              <form v-else @submit.prevent="merchantReview('APPROVE')">
                <label class="field"
                  >独立复核理由<textarea
                    v-model="form.reviewReason"
                    required
                    rows="3"
                  />
                </label>
                <div class="actions">
                  <button
                    :disabled="busy || !canReviewApplication"
                  >
                    通过模拟申请</button
                  ><button
                    class="secondary"
                    type="button"
                    :disabled="busy || !canReviewApplication"
                    @click="merchantReview('REJECT')"
                  >
                    拒绝并记录理由</button
                  ><button
                    class="secondary"
                    type="button"
                    :disabled="busy || !canReviewApplication"
                    @click="applicationLifecycle('requestInformation')"
                  >
                    要求补充合成材料
                  </button>
                </div>
              </form>
              <div class="next-hint">
                {{
                  isReviewer
                    ? "完成复核后，切回商家申请人创建门店"
                    : "提交后切换「模拟入驻复核人」，选择刚创建的申请继续"
                }}
                →
              </div>
            </template>
            <template v-if="stage === 1"
              ><div class="section-top">
                <div>
                  <span class="eyebrow">STEP 02 · CATALOG</span>
                  <h2>创建门店与模拟套餐</h2>
                </div>
                <span class="pill">商家角色</span>
              </div>
              <p>
                先选择通过模拟入驻的商家，再创建门店和套餐。价格用整数分输入。
              </p>
              <div v-if="!isMerchant" class="notice">
                请切换「模拟商家申请人」，通过入驻后将获得 OWNER 范围权限。
              </div>
              <form @submit.prevent="storeCreate">
                <div class="form-grid">
                  <label class="field full-width"
                    >已批准商家<select v-model="flow.merchantUid" required>
                      <option value="">请选择</option>
                      <option
                        v-for="m in resources.merchants || []"
                        :key="m.id"
                        :value="m.uid"
                      >
                        {{ m.name }} · {{ m.uid }} · {{ statusText(m.status) }}
                      </option>
                    </select></label
                  ><label class="field"
                    >门店名称<input v-model="form.storeName" required /></label
                  ><label class="field"
                    >模拟地址<input v-model="form.addressText" required
                  /></label>
                </div>
                <button :disabled="busy || !canCreateStore">创建模拟门店</button>
              </form>
              <hr />
              <form @submit.prevent="productCreate">
                <div class="form-grid">
                  <label class="field full-width"
                    >门店<select
                      :value="flow.store?.id || ''"
                      @change="setSelection('stores', $event.target.value)"
                      required
                    >
                      <option value="">请选择</option>
                      <option
                        v-for="s in resources.stores || []"
                        :value="s.id"
                        :key="s.id"
                      >
                        {{ s.name }} · #{{ s.id }}
                      </option>
                    </select></label
                  ><label class="field"
                    >套餐名称<input
                      v-model="form.productName"
                      required /></label
                  ><label class="field"
                    >价格（整数分）<input
                      v-model="form.priceMinor"
                      inputmode="numeric"
                      pattern="[0-9]+"
                      required
                    /><small>{{
                      Number.isSafeInteger(Number(form.priceMinor))
                        ? money(Number(form.priceMinor))
                        : "请输入整数分"
                    }}</small></label
                  >
                </div>
                <button :disabled="busy || !canCreateProduct">
                  创建模拟套餐
                </button>
              </form></template
            >
            <template v-if="stage === 2"
              ><div class="section-top">
                <div>
                  <span class="eyebrow">STEP 03 · ORDER</span>
                  <h2>
                    {{
                      isConsumer ? "读取并逐笔确认订单" : "创建订单与发布凭证"
                    }}
                  </h2>
                </div>
                <span class="pill">{{ statusText(flow.order?.status) }}</span>
              </div>
              <template v-if="!isConsumer"
                ><form @submit.prevent="orderCreate">
                  <div class="form-grid">
                    <label class="field"
                      >选择门店<select
                        :value="flow.store?.id || ''"
                        @change="setSelection('stores', $event.target.value)"
                        required
                      >
                        <option value="">请选择</option>
                        <option
                          v-for="s in resources.stores || []"
                          :key="s.id"
                          :value="s.id"
                        >
                          {{ s.name }}
                        </option>
                      </select></label
                    ><label class="field"
                      >选择套餐<select
                        :value="flow.product?.id || ''"
                        @change="setSelection('products', $event.target.value)"
                        required
                      >
                        <option value="">请选择</option>
                        <option
                          v-for="p in (resources.products || []).filter(
                            (p) => p.storeId === flow.store?.id,
                          )"
                          :value="p.id"
                          :key="p.id"
                        >
                          {{ p.name }} · {{ money(p.priceMinor) }}
                        </option>
                      </select></label
                    ><label class="field"
                      >数量<input
                        v-model="form.quantity"
                        type="number"
                        min="1"
                        max="99"
                        required
                    /></label>
                  </div>
                  <button :disabled="busy || !canCreateOrder || !flow.product">
                    创建订单草稿
                  </button>
                </form>
                <div class="actions spaced">
                  <button
                    class="secondary"
                    @click="publish"
                    :disabled="
                      busy || !canManageOrder || flow.order?.status !== 'DRAFT'
                    "
                  >
                    发布确认凭证</button
                  ><button
                    class="text-button"
                    @click="cancelOrder"
                    :disabled="
                      busy ||
                      !canManageOrder ||
                      !['DRAFT', 'PUBLISHED', 'CONFIRMED'].includes(
                        flow.order?.status,
                      )
                    "
                  >
                    取消此订单
                  </button>
                </div>
                <div v-if="flow.confirmCredential" class="credential-box">
                  <b>确认凭证已生成</b>
                  <p>
                    切换消费者后，凭证会保留在本页内存。有效至
                    {{
                      shanghaiDateTime(flow.confirmCredential.expiresAt)
                    }}
                  </p>
                  <input
                    :value="flow.confirmCredential.credential"
                    readonly
                    aria-label="订单确认凭证"
                  /></div
              ></template>
              <template v-else
                ><div v-if="!account" class="activation">
                  <h3>开通本次模拟资格</h3>
                  <label class="checkbox"
                    ><input
                      type="checkbox"
                      v-model="form.activationAccepted"
                    /><span
                      >我已阅读模拟告知
                      {{
                        fixtures.agreementVersion
                      }}，知悉仅为隔离测试，不产生真实债务、付款或金融服务。</span
                    ></label
                  ><button
                    class="secondary"
                    :disabled="busy || !form.activationAccepted"
                    @click="activate"
                  >
                    开通模拟资格
                  </button>
                </div>
                <form @submit.prevent="resolveOrder">
                  <label class="field"
                    >商家提供的确认凭证<input
                      v-model="form.credential"
                      required
                      autocomplete="off" /></label
                  ><button class="secondary" :disabled="busy">
                    读取订单快照
                  </button>
                </form>
                <div
                  v-if="flow.order?.status === 'PUBLISHED'"
                  class="order-snapshot"
                >
                  <div class="row">
                    <b>订单 #{{ flow.order.id }}</b
                    ><strong>{{ money(flow.order.totalMinor) }}</strong>
                  </div>
                  <div
                    v-for="(item, i) in flow.order.items"
                    :key="i"
                    class="line-item"
                  >
                    <span>{{ item.name }} × {{ item.quantity }}</span
                    ><span>{{ money(item.subtotalMinor) }}</span>
                  </div>
                  <small
                    >商家 {{ flow.order.merchantUid }} · 规则
                    {{ flow.order.ruleCode }} · 版本
                    {{ flow.order.version }}</small
                  ><label class="checkbox"
                    ><input
                      v-model="form.confirmAccepted"
                      type="checkbox"
                    /><span
                      >我已核对以上商品及模拟金额，并同意模拟协议
                      {{ fixtures.agreementVersion }}，确认本笔模拟预占。</span
                    ></label
                  ><button
                    @click="confirm"
                    :disabled="
                      busy ||
                      !form.confirmAccepted ||
                      !account ||
                      resolvedBy !== persona
                    "
                  >
                    确认本笔模拟订单
                  </button>
                </div></template
              ></template
            >
            <template v-if="stage === 3"
              ><div class="section-top">
                <div>
                  <span class="eyebrow">STEP 04 · FULFILLMENT</span>
                  <h2>使用独立核销凭证履约</h2>
                </div>
                <span class="pill">{{ statusText(flow.order?.status) }}</span>
              </div>
              <p>
                消费者取核销凭证后，商家执行核销。模拟预占才会转为应收并生成凭证。
              </p>
              <label class="field"
                >当前身份的订单<select
                  :value="flow.order?.id || ''"
                  @change="setSelection('orders', $event.target.value)"
                >
                  <option value="">选择订单</option>
                  <option
                    v-for="o in resources.orders || []"
                    :key="o.id"
                    :value="o.id"
                  >
                    #{{ o.id }} · {{ statusText(o.status) }} ·
                    {{ money(o.totalMinor) }}
                  </option>
                </select></label
              >
              <div class="workflow-action">
                <span class="action-number">1</span>
                <div>
                  <h3>消费者取得核销凭证</h3>
                  <p>一次性凭证仅用于当前订单版本，不与确认凭证混用</p>
                  <button
                    :disabled="
                      busy || !ownsOrder || flow.order?.status !== 'CONFIRMED'
                    "
                    @click="fulfillmentCredential"
                  >
                    取得核销凭证
                  </button>
                </div>
              </div>
              <div v-if="flow.fulfillCredential" class="credential-box">
                <b>核销凭证已保存在当前页面</b>
                <p>
                  有效至
                  {{
                    shanghaiDateTime(flow.fulfillCredential.expiresAt)
                  }}，请切换商家继续
                </p>
              </div>
              <div class="workflow-action">
                <span class="action-number">2</span>
                <div>
                  <h3>商家核销并生成模拟应收</h3>
                  <p>请确认演示交付完成，再进行此模拟操作</p>
                  <button
                    :disabled="
                      busy ||
                      !canManageOrder ||
                      !flow.fulfillCredential ||
                      flow.order?.status !== 'CONFIRMED'
                    "
                    @click="fulfill"
                  >
                    核销并生成模拟应收
                  </button>
                </div>
              </div>
              <div v-if="flow.receivable" class="receipt-card">
                <span>模拟应收 #{{ flow.receivable.id }}</span
                ><strong>{{ money(flow.receivable.outstandingMinor) }}</strong
                ><small
                  >{{ statusText(flow.receivable.status) }} · 由后端返回</small
                >
              </div></template
            >
            <template v-if="stage === 4"
              ><div class="section-top">
                <div>
                  <span class="eyebrow">STEP 05 · PAYMENT</span>
                  <h2>还款申请与模拟渠道结果</h2>
                </div>
                <span class="pill">不调用真实支付</span>
              </div>
              <template v-if="!isController"
                ><p>
                  消费者提交模拟还款后，等待结果控制员注入事件。提交申请本身不会减少本金。
                </p>
                <form @submit.prevent="repay">
                  <label class="field"
                    >选择未结模拟应收<select
                      :value="flow.receivable?.id || ''"
                      @change="setSelection('receivables', $event.target.value)"
                      required
                    >
                      <option value="">请选择</option>
                      <option
                        v-for="r in resources.receivables || []"
                        :key="r.id"
                        :value="r.id"
                      >
                        #{{ r.id }} · 待还 {{ money(r.outstandingMinor) }} ·
                        {{ r.merchantUid }}
                      </option>
                    </select></label
                  ><label class="field"
                    >本次模拟还款（整数分）<input
                      v-model="form.repayMinor"
                      inputmode="numeric"
                      pattern="[0-9]+"
                      required /></label
                  ><button :disabled="busy || !ownsReceivable || !flow.receivable?.outstandingMinor">
                    提交模拟还款
                  </button>
                </form></template
              >
              <form v-else @submit.prevent="resultControl">
                <p>
                  仅 SIM_CONTROLLER
                  身份可发送测试事件。成功结果由后端校验、分配并记账。
                </p>
                <label class="field"
                  >模拟还款记录<select
                    :value="flow.repayment?.id || ''"
                    @change="setSelection('repayments', $event.target.value)"
                    required
                  >
                    <option value="">请选择</option>
                    <option
                      v-for="r in resources.repayments || []"
                      :value="r.id"
                      :key="r.id"
                    >
                      #{{ r.id }} · {{ statusText(r.status) }} ·
                      {{ money(r.amountMinor) }}
                    </option>
                  </select></label
                >
                <div class="form-grid">
                  <label class="field"
                    >测试结果<select v-model="form.result">
                      <option value="PAYMENT_CONFIRMED">
                        成功 · PAYMENT_CONFIRMED
                      </option>
                      <option value="FAILED">失败 · FAILED</option>
                      <option value="CANCELLED">取消 · CANCELLED</option>
                      <option value="TIMEOUT">超时 · TIMEOUT</option>
                    </select></label
                  ><label
                    v-if="form.result !== 'PAYMENT_CONFIRMED'"
                    class="field"
                    >测试原因<input v-model="form.failureReason" required
                  /></label>
                </div>
                <label v-if="form.result === 'PAYMENT_CONFIRMED'" class="field"
                  >渠道结果与回调<select v-model="form.callbackMode">
                    <option value="immediate">原入口：成功并立即回调</option>
                    <option value="withhold">模拟丢回调：仅记录渠道成功</option>
                    <option value="provider-deliver">
                      补充入口：记录渠道结果并回调
                    </option></select
                  ><small
                    >暂缓回调时，本地还款和应收可能仍未改变，必须以刷新后的后端状态为准</small
                  ></label
                >
                <button :disabled="busy || !canControlRepayment">
                  注入模拟结果
                </button>
                <div v-if="heldProviderEvent" class="credential-box">
                  <b>本页保留了一个暂缓回调的测试事件</b>
                  <p>
                    业务号 {{ heldProviderEvent.event.reference }} · 事件
                    {{ heldProviderEvent.event.providerEventId }}
                  </p>
                  <button
                    class="secondary"
                    type="button"
                    @click="deliverHeldCallback"
                    :disabled="busy || heldProviderEvent.phone !== persona"
                  >
                    投递刚才暂缓的回调
                  </button>
                </div>
              </form>
              <div v-if="flow.repayment" class="receipt-card">
                <span>模拟还款 #{{ flow.repayment.id }}</span
                ><strong>{{ statusText(flow.repayment.status) }}</strong
                ><small
                  >{{ money(flow.repayment.amountMinor) }} ·
                  {{ flow.repayment.reference }}</small
                >
              </div>
              <div class="next-hint">
                完成后切回消费者查看可用额度，或打开「数据与账务」核对记录 →
              </div></template
            >
          </article>
          <aside class="workflow-summary panel">
            <span class="eyebrow">DEMO CHECKPOINTS</span>
            <h3>本页演示进度</h3>
            <p>以下为当前演示保留的记录引用，切换身份后各操作仍由后端鉴权。</p>
            <dl>
              <dt>模拟入驻</dt>
              <dd>
                {{
                  flow.application
                    ? `#${flow.application.id} · ${statusText(flow.application.status)}`
                    : "尚未选择"
                }}
              </dd>
              <dt>商家 / 门店</dt>
              <dd>
                {{ flow.merchantUid || "尚未选择"
                }}<small>{{ flow.store?.name || "未选择门店" }}</small>
              </dd>
              <dt>模拟套餐</dt>
              <dd>
                {{ flow.product?.name || "尚未创建"
                }}<small>{{
                  flow.product ? money(flow.product.priceMinor) : ""
                }}</small>
              </dd>
              <dt>订单 / 应收</dt>
              <dd>
                {{ flow.order ? `订单 #${flow.order.id}` : "尚未创建"
                }}<small>{{
                  flow.receivable
                    ? `应收 #${flow.receivable.id}`
                    : "未生成模拟应收"
                }}</small>
              </dd>
            </dl>
            <button class="secondary full" @click="page = 'data'">
              查看后端记录 ↗
            </button>
            <div class="summary-foot">
              最新同步（北京时间） {{ lastRefresh || "尚未读取"
              }}<br />不会用前端估算替代账务结果
            </div>
          </aside>
        </section>
      </template>
      <template v-if="page === 'data'"
        ><div class="section-heading">
          <div>
            <h2>当前身份可见的数据</h2>
            <p>
              由后端按角色与范围过滤，最多返回各类
              {{ workspace?.resourceLimit || 100 }} 条
            </p>
          </div>
          <span class="pill">{{ selectedPersona?.uid || "未登录" }}</span>
        </div>
        <div class="data-toolbar">
          <select
            v-model="resourceType"
            @change="
              recordDetail = null;
              filter = '';
            "
            aria-label="数据类型"
          >
            <option
              v-for="[key, title] in resourceTypes"
              :key="key"
              :value="key"
            >
              {{ title }}（{{
                key === "scopeGrants"
                  ? workspace?.scopeGrants?.length || 0
                  : resources[key]?.length || 0
              }}）
            </option></select
          ><input
            v-model="filter"
            placeholder="按当前记录内容筛选…"
            aria-label="筛选记录"
          />
        </div>
        <article class="panel table-panel">
          <div v-if="!session || !rows.length" class="empty-state">
            <div class="empty-symbol">▤</div>
            <h3>
              {{ !session ? "请先登录测试身份" : "当前范围暂无匹配记录" }}
            </h3>
            <p>没有读取到记录不代表余额为零。可切换身份、刷新或调整筛选。</p>
          </div>
          <div v-else class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th v-for="col in columns" :key="col">
                    {{
                      labels[col] ||
                      { totalMinor: "订单金额", outstandingMinor: "待还金额", attemptCount: "累计尝试", cycleAttemptCount: "本轮尝试", cycle: "重试轮次", nextAttemptAt: "下次投递" }[
                        col
                      ] ||
                      col
                    }}
                  </th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.id">
                  <td v-for="col in columns" :key="col">
                    <span v-if="col === 'status'" class="pill">{{
                      statusText(row[col])
                    }}</span
                    ><span v-else>{{ valueText(col, row[col]) }}</span>
                  </td>
                  <td>
                    <button class="text-button" @click="recordDetail = row">
                      详情</button
                    ><button
                      v-if="
                        resourceType === 'exports' && row.status === 'READY'
                      "
                      class="text-button"
                      @click="downloadExport(row)"
                      :disabled="busy"
                    >
                      下载</button
                    ><button
                      v-if="
                        [
                          'applications',
                          'stores',
                          'products',
                          'orders',
                          'receivables',
                          'repayments',
                        ].includes(resourceType)
                      "
                      class="text-button"
                      @click="
                        setSelection(resourceType, row.id);
                        page = 'workflow';
                      "
                    >
                      用于流程
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>
        <article v-if="recordDetail" class="panel result-panel">
          <div class="section-top">
            <h3>记录详情 · #{{ recordDetail.id }}</h3>
            <button class="text-button" @click="recordDetail = null">
              关闭
            </button>
          </div>
          <pre>{{ JSON.stringify(redact(recordDetail), null, 2) }}</pre>
          <button
            v-if="resourceType === 'applications'"
            class="secondary"
            @click="
              setSelection('applications', recordDetail.id);
              stage = 0;
              page = 'workflow';
            "
          >
            在入驻流程查看 / 补件
          </button>
        </article>
        <p class="muted">
          高级操作支持订单、应收的服务端分页与筛选，以及退款、异议、调账、对账、导出、日结和权限管理。
        </p></template
      >
      <template v-if="page === 'contract'"
        ><div class="notice">
          这是面向验证人员的完整契约表单。请确认当前角色、作用范围、对象版本与审批独立性；未配置的真实微信服务会明确报错。
        </div>
        <ContractExplorer
          :busy="busy"
          :context="context"
          @execute="contractExecute"
        />
        <details class="panel supplemental-panel">
          <summary>补充验证工具 · 渠道对账单与迟到分配</summary>
          <p>
            这些是隔离模拟环境的补充接口。渠道文件需账务或结果控制权限；迟到分配需要独立申请和复核。
          </p>
          <form @submit.prevent="channelBill">
            <div class="form-grid">
              <label class="field"
                >商家 UID<input
                  v-model="supplemental.merchantUid"
                  :placeholder="flow.merchantUid || '例如 s0000101'" /></label
              ><label class="field"
                >营业日<input
                  v-model="supplemental.businessDate"
                  type="date"
                  required
              /></label>
            </div>
            <button class="secondary" :disabled="busy || !session">
              下载模拟渠道 CSV
            </button>
          </form>
          <hr />
          <div class="form-grid">
            <label class="field"
              >对账差异 ID<input v-model="supplemental.differenceId" /></label
            ><label class="field"
              >模拟还款 ID<input v-model="supplemental.repaymentId"
            /></label>
          </div>
          <button
            class="secondary"
            @click="lateAllocation"
            :disabled="busy || !session"
          >
            申请迟到分配
          </button>
          <hr />
          <div class="form-grid">
            <label class="field"
              >迟到分配申请 ID<input
                v-model="supplemental.lateRequestId" /></label
            ><label class="field"
              >当前版本<input
                v-model="supplemental.version"
                inputmode="numeric" /></label
            ><label class="field full-width"
              >独立复核理由<input v-model="supplemental.reason"
            /></label>
          </div>
          <div class="actions">
            <button
              @click="lateDecision('APPROVE')"
              :disabled="busy || !session"
            >
              批准迟到分配</button
            ><button
              class="secondary"
              @click="lateDecision('REJECT')"
              :disabled="busy || !session"
            >
              拒绝
            </button>
          </div>
        </details>
        <NotificationControls
          :busy="busy"
          :authorized="isPlatformController"
          :setting="workspace?.notificationFailureConfig"
          :deliveries="resources.notificationOutbox || []"
          @command="execute"
        />
        <article v-if="lastResult" class="panel result-panel">
          <div class="section-top">
            <h3>{{ lastResult.title }} · 后端返回</h3>
            <small>{{ lastResult.requestId || "模拟辅助接口" }}</small>
          </div>
          <pre>{{ JSON.stringify(lastResult.data, null, 2) }}</pre>
        </article></template
      >
      <template v-if="page === 'activity'"
        ><article class="panel">
          <div class="section-top">
            <div>
              <h2>当前页面的请求记录</h2>
              <p>
                仅保留最近 40
                次摘要，凭证和令牌已隐藏；完整审计请用高级操作读取。
              </p>
            </div>
            <span class="pill">{{ logs.length }} 条</span>
          </div>
          <div v-if="!logs.length" class="empty-state">还没有执行操作</div>
          <details v-for="(entry, i) in logs" :key="i" class="activity-entry">
            <summary>
              <span class="activity-dot" :class="{ failed: !entry.ok }"></span
              ><b>{{ entry.title }}</b
              ><span>{{ entry.persona }}</span
              ><time>{{ entry.at }}</time
              ><span class="pill">{{ entry.ok ? "成功" : "失败" }}</span>
            </summary>
            <pre>{{ JSON.stringify(entry.detail, null, 2) }}</pre>
          </details>
        </article></template
      >
      <footer>
        <span>统一 API · 隔离测试环境</span
        ><span
          >轻量模拟验证界面；完整芋道 PC
          后台与真实小程序端验收仍需单独完成</span
        >
      </footer>
    </main>
  </div>
</template>
