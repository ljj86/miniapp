<script setup>
import { computed, ref } from 'vue';
import { safePath } from '../api.js';
import { shanghaiDateTime } from '../dates.js';
import { notificationFailureBody, notificationRetryCommand, canRetryNotification, requirePermission } from '../supplemental.js';
const props = defineProps({
  busy: Boolean,
  authorized: Boolean,
  setting: Object,
  deliveries: { type: Array, default: () => [] },
});
const emit = defineEmits(['command']);
const failures = ref('0'), selectedId = ref(''), error = ref('');
const failureReason = ref('模拟站内通知故障恢复验证'), retryReason = ref('独立核查死信后重新投递模拟通知');
const selected = computed(() => props.deliveries.find(row => row.id === selectedId.value));
function configure() {
  try {
    error.value = '';
    requirePermission(props.authorized && !props.busy);
    emit('command', { title: '设置模拟通知投递失败次数', path: '/api/v1/simulation-controls/notification-failures', method: 'POST', body: notificationFailureBody(failures.value, props.setting, failureReason.value) });
  } catch (e) { error.value = e.message; }
}
function retry() {
  try {
    error.value = '';
    emit('command', notificationRetryCommand(selected.value, retryReason.value, props.authorized && !props.busy));
  } catch (e) { error.value = e.message; }
}
function inspect(kind) {
  if (props.busy || !selected.value || !['attempts', 'retries'].includes(kind)) return;
  emit('command', { title: kind === 'attempts' ? '读取通知投递尝试' : '读取死信重试审计', path: safePath(`/api/v1/notification-outbox/{id}/${kind}`, { id: selected.value.id }), method: 'GET' });
}
</script>
<template>
  <details class="panel supplemental-panel">
    <summary>模拟站内通知 · 故障与死信重试</summary>
    <p>仅具有平台级 SIM_CONTROLLER 范围的测试身份可使用。此控制只影响隔离站内通知，不会发送真实短信、邮件或外部消息。</p>
    <div v-if="!authorized" class="notice">当前身份没有已读取的平台级控制范围。请切换测试结果控制员并刷新，最终授权仍由后端检查。</div>
    <div v-else-if="!setting" class="notice">尚未读取故障配置版本，请刷新后继续。</div>
    <p v-if="setting" class="muted">服务端剩余失败次数 {{ setting.remainingFailures }} · 配置版本 {{ setting.version }}</p>
    <form @submit.prevent="configure">
      <div class="form-grid">
        <label class="field">后续模拟投递失败次数<input v-model="failures" inputmode="numeric" type="text" pattern="[0-9]+" required /><small>0 表示恢复正常；允许 0–100 次，每次消耗都会更新配置版本</small></label>
        <label class="field">故障测试原因<input v-model="failureReason" maxlength="500" required /></label>
      </div>
      <button class="secondary" :disabled="busy || !authorized || !setting">设置投递故障次数</button>
    </form>
    <hr />
    <form @submit.prevent="retry">
      <label class="field">当前范围的投递记录<select v-model="selectedId" required>
        <option value="">请选择投递记录</option>
        <option v-for="row in deliveries" :key="row.id" :value="row.id">#{{ row.id }} · {{ row.status }} · 已尝试 {{ row.attemptCount }} 次</option>
      </select></label>
      <div v-if="selected" class="delivery-summary">
        <span>状态 <b>{{ selected.status }}</b></span><span>累计尝试 <b>{{ selected.attemptCount }}</b></span><span>本轮尝试 <b>{{ selected.cycleAttemptCount }}</b></span><span>投递轮次 <b>{{ selected.cycle }}</b></span><span>下一次 <b>{{ shanghaiDateTime(selected.nextAttemptAt) }}</b></span><span>错误码 <b>{{ selected.lastError || '无' }}</b></span><span>当前版本 <b>{{ selected.version }}</b></span>
      </div>
      <div class="actions"><button type="button" class="secondary" :disabled="busy || !selected" @click="inspect('attempts')">读取投递尝试</button><button type="button" class="secondary" :disabled="busy || !selected" @click="inspect('retries')">读取重试审计</button></div>
      <label class="field">死信重试原因<input v-model="retryReason" maxlength="500" required /></label>
      <button :disabled="busy || !authorized || !canRetryNotification(selected)">重试所选死信</button>
      <p class="muted">RETRY_WAIT 由后端自动重试；只有 DEAD 可手动开启新一轮投递，每轮最多 3 次。总尝试次数保留，不在前端推算投递结果。</p>
    </form>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
  </details>
</template>
