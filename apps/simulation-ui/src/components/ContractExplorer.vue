<script setup>
import { computed, ref, watch } from "vue";
import {
  operations,
  schemas,
  resolve,
  defaultValue,
  operationDetails,
  labels,
} from "../contract";
const props = defineProps({ busy: Boolean, context: Object });
const emit = defineEmits(["execute"]);
const search = ref(""),
  version = ref(""),
  selected = ref("getCurrentRule"),
  values = ref({}),
  parameters = ref({}),
  file = ref(null),
  error = ref(""),
  variant = ref(0);
const choices = computed(() =>
  operations.filter(
    (o) =>
      (!version.value || o.version === version.value) &&
      `${o.title} ${o.path} ${o.roles}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
);
const op = computed(() => operations.find((o) => o.id === selected.value));
const details = computed(() => operationDetails(op.value));
const schema = computed(() => {
  const raw = schemas[op.value.request];
  return raw?.oneOf ? resolve(raw.oneOf[variant.value]) : details.value.schema;
});
const fields = computed(() =>
  Object.entries(schema.value.properties || {}).map(([name, s]) => ({
    name,
    ...resolve(s),
    required: (schema.value.required || []).includes(name),
  })),
);
function populate() {
  error.value = "";
  values.value = {};
  parameters.value = {};
  file.value = null;
  for (const f of fields.value) {
    let v = props.context?.[f.name] ?? defaultValue(f);
    values.value[f.name] = ["object", "array"].includes(f.type)
      ? JSON.stringify(v, null, 2)
      : v;
  }
  for (const p of details.value.parameters)
    parameters.value[p.name] =
      props.context?.[p.name] ??
      (p.required ? "" : (resolve(p.schema).default ?? ""));
}
watch(
  selected,
  () => {
    variant.value = 0;
    populate();
  },
  { immediate: true },
);
watch(variant, populate);
function submit() {
  try {
    error.value = "";
    let body;
    if (op.value.request) {
      if (fields.value.some((f) => f.format === "binary")) {
        if (!file.value) throw Error("请选择模拟材料文件");
        body = new FormData();
        body.append("file", file.value);
        body.append("purpose", values.value.purpose);
      } else {
        body = {};
        for (const f of fields.value) {
          const v = values.value[f.name];
          if (!f.required && (v === "" || v === undefined)) continue;
          if (f.required && f.type === "string" && !String(v).trim())
            throw Error(`请填写${labels[f.name] || f.name}`);
          if (["object", "array"].includes(f.type))
            body[f.name] = JSON.parse(v);
          else if (f.type === "integer") {
            if (!/^\d+$/.test(String(v)) || !Number.isSafeInteger(Number(v)))
              throw Error(`${f.name} 必须为安全整数`);
            body[f.name] = Number(v);
          } else body[f.name] = v;
        }
      }
    }
    const params = {},
      query = {};
    for (const p of details.value.parameters)
      (p.in === "path" ? params : query)[p.name] = parameters.value[p.name];
    emit("execute", { operation: op.value, body, params, query });
  } catch (e) {
    error.value = e.message;
  }
}
</script>
<template>
  <div class="contract-layout">
    <aside class="operation-picker panel">
      <h3>契约操作</h3>
      <p class="muted">按版本和业务检索，表单直接来自 API 契约</p>
      <input
        v-model="search"
        aria-label="搜索接口"
        placeholder="搜索：退款、日结、规则…"
      /><select v-model="version" aria-label="版本">
        <option value="">全部版本</option>
        <option
          v-for="v in [...new Set(operations.map((o) => o.version))]"
          :key="v"
        >
          {{ v }}
        </option>
      </select>
      <div class="operation-list">
        <button
          v-for="o in choices"
          :key="o.id"
          class="operation-choice"
          :class="{ selected: o.id === selected }"
          @click="selected = o.id"
          :disabled="busy"
        >
          <span>{{ o.title }}</span
          ><small>{{ o.version }} · {{ o.method }}</small>
        </button>
      </div>
    </aside>
    <article class="panel contract-form">
      <div class="section-top">
        <div>
          <span class="eyebrow">{{ op.version }} · {{ op.method }}</span>
          <h2>{{ op.title }}</h2>
        </div>
        <span class="pill">{{ op.roles }}</span>
      </div>
      <p class="api-path">{{ op.path }}</p>
      <div v-if="op.id === 'receiveMockCallback'" class="notice">
        签名回调是服务端集成入口，不在浏览器保存签名密钥。请使用「模拟结果控制」提交测试事件；回调验签由后端测试覆盖。
      </div>
      <form v-else @submit.prevent="submit">
        <div v-if="schemas[op.request]?.oneOf" class="field">
          <label>请求类型</label
          ><select v-model="variant">
            <option
              v-for="(v, i) in schemas[op.request].oneOf"
              :value="i"
              :key="i"
            >
              {{ v.$ref.split("/").pop() }}
            </option>
          </select>
          <p class="muted">
            微信身份服务未配置时会明确返回不可用，不会伪装为真实微信登录
          </p>
        </div>
        <div class="form-grid">
          <label v-for="p in details.parameters" :key="p.name" class="field"
            >{{ labels[p.name] || p.name
            }}<small
              >{{ p.in === "path" ? "路径参数" : "可选筛选"
              }}{{ p.required ? " · 必填" : "" }}</small
            ><select v-if="resolve(p.schema).enum" v-model="parameters[p.name]">
              <option value="">请选择</option>
              <option v-for="v in resolve(p.schema).enum" :key="v">
                {{ v }}
              </option></select
            ><input
              v-else
              v-model="parameters[p.name]"
              :required="p.required"
              :type="resolve(p.schema).format === 'date' ? 'date' : 'text'"
          /></label>
          <label
            v-for="f in fields"
            :key="f.name"
            class="field"
            :class="{ 'full-width': ['array', 'object'].includes(f.type) }"
            >{{ labels[f.name] || f.name
            }}<small
              >{{ f.description || f.name
              }}{{ f.required ? " · 必填" : " · 可选" }}</small
            ><input
              v-if="f.format === 'binary'"
              type="file"
              @change="file = $event.target.files[0]"
              required /><select v-else-if="f.enum" v-model="values[f.name]">
              <option v-for="v in f.enum" :key="v">{{ v }}</option></select
            ><select v-else-if="f.type === 'boolean'" v-model="values[f.name]">
              <option :value="false">否</option>
              <option :value="true">是</option></select
            ><textarea
              v-else-if="['array', 'object'].includes(f.type)"
              v-model="values[f.name]"
              rows="4"
              spellcheck="false" /><input
              v-else
              v-model="values[f.name]"
              :required="f.required"
              :type="f.format === 'date' ? 'date' : 'text'"
              :inputmode="f.type === 'integer' ? 'numeric' : undefined"
              :maxlength="f.maxLength"
          /></label>
        </div>
        <p
          v-if="!fields.length && !details.parameters.length"
          class="empty-inline"
        >
          此接口无需填写参数，返回当前身份可读取的数据
        </p>
        <p v-if="error" role="alert" class="error-text">{{ error }}</p>
        <div class="actions">
          <button :disabled="busy">
            {{
              busy
                ? "处理中…"
                : op.method === "GET"
                  ? "读取数据"
                  : "提交模拟操作"
            }}</button
          ><button
            class="secondary"
            type="button"
            @click="populate"
            :disabled="busy"
          >
            填入当前工作流信息
          </button>
        </div>
        <p class="muted">
          写操作自动携带幂等键。所填 ID
          保持字符串，金额使用整数分。权限和版本由后端再次验证。
        </p>
      </form>
    </article>
  </div>
</template>
