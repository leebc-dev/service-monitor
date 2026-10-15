<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  getInfrastructure,
  getServices,
  getMetrics,
  getAlerts,
  getAnalytics,
  type InfrastructureHealth,
  type MonitoredService,
  type RequestMetrics,
  type AlertsResponse,
  type AlertNotification,
  type AnalyticsResponse,
  type AnalyticsRoute,
  type RecentEvent,
} from "./api";
import MetricChart from "./components/MetricChart.vue";
import RouteTable from "./components/RouteTable.vue";
import NavIcon from "./components/NavIcon.vue";

const pages = [
  {
    id: "overview",
    label: "서비스 개요",
    icon: "◫",
    hint: "요청 흐름과 성능을 한눈에 확인하세요.",
  },
  {
    id: "endpoints",
    label: "엔드포인트",
    icon: "⌘",
    hint: "느린 API와 오류가 집중되는 경로를 찾아보세요.",
  },
  {
    id: "requests",
    label: "요청 탐색",
    icon: "≡",
    hint: "개별 요청을 좁혀 보고 응답 상태와 처리 시간을 확인하세요.",
  },
  {
    id: "alerts",
    label: "알림 센터",
    icon: "♧",
    hint: "기준 초과 이력과 이메일 발송 결과를 확인하세요.",
  },
  {
    id: "infrastructure",
    label: "인프라 상태",
    icon: "▱",
    hint: "수집과 알림을 처리하는 인프라의 연결 상태입니다.",
  },
];
const page = ref("overview");
function syncHash() {
  const value = location.hash.slice(1);
  page.value = pages.some((p) => p.id === value) ? value : "overview";
}
syncHash();
const currentPage = computed(() => pages.find((p) => p.id === page.value)!);
const infrastructure = ref<InfrastructureHealth | null>(null);
const services = ref<MonitoredService[]>([]);
const live = ref<RequestMetrics | null>(null);
const analytics = ref<AnalyticsResponse | null>(null);
const alerts = ref<AlertsResponse | null>(null);
const selection = ref("football-lounge|development");
const serviceKey = computed(() => selection.value.split("|")[0]!);
const environment = computed(() => selection.value.split("|")[1]!);
const selectedService = computed(() =>
  services.value.find(
    (s) =>
      s.serviceKey === serviceKey.value && s.environment === environment.value,
  ),
);
const minutes = ref(15);
const loading = ref(false);
const errors = ref<string[]>([]);
const lastChecked = ref<Date | null>(null);
const now = ref(Date.now());
const autoRefresh = ref(true);
const nextRefresh = ref(Date.now() + 15000);
const refreshIn = computed(() =>
  Math.min(15, Math.max(0, Math.ceil((nextRefresh.value - now.value) / 1000))),
);
let interval: ReturnType<typeof setInterval>;
let generation = 0;
let seen: Set<string> | null = null;
const newAlerts = ref(0);
const summary = computed(() => analytics.value?.summary);
const dependencies = [
  {
    key: "db",
    name: "MySQL",
    icon: "DB",
    role: "요청 이력과 알림 보관",
    detail: "요청 이력 · 시계열 조회 · 이메일 발송 대기열",
  },
  {
    key: "redis",
    name: "Redis",
    icon: "R",
    role: "실시간 지표와 반복 억제",
    detail: "최근 5분 집계 · 같은 알림 10분 반복 억제",
  },
  {
    key: "kafka",
    name: "Kafka",
    icon: "K",
    role: "요청 이벤트 전달",
    detail: "요청 이력 · 실시간 집계 · 알림의 독립 소비",
  },
];
const connected = computed(
  () =>
    dependencies.filter(
      (d) => infrastructure.value?.components?.[d.key]?.status === "UP",
    ).length,
);
const liveStatus = computed(() => {
  if (!live.value || !alerts.value)
    return {
      label: "상태 확인 불가",
      tone: "muted",
      detail: "지표 연결을 확인하세요",
    };
  if (!live.value.requestCount)
    return {
      label: "최근 5분 요청 없음",
      tone: "muted",
      detail: "가용성은 별도 확인이 필요합니다",
    };
  if (live.value.requestCount < alerts.value.policy.minimumRequests)
    return {
      label: "판단 표본 수집 중",
      tone: "amber",
      detail: `${live.value.requestCount} / ${alerts.value.policy.minimumRequests}건`,
    };
  if (
    (live.value.serverErrorRate ?? 0) >= alerts.value.policy.errorRatePercent ||
    (live.value.averageDurationMs ?? 0) >= alerts.value.policy.averageDurationMs
  )
    return {
      label: "기준 초과 감지",
      tone: "danger",
      detail: "최근 5분의 요청 기준",
    };
  return {
    label: "알림 기준 이내",
    tone: "green",
    detail: "최근 5분의 요청 기준",
  };
});
const emailLabels = {
  PENDING: "발송 대기",
  SENDING: "발송 중",
  RETRY: "재시도 대기",
  SENT: "SMTP 접수 완료",
  FAILED: "발송 실패",
};
const emailErrors: Record<string, string> = {
  SMTP_AUTH: "메일 계정 인증 실패",
  SMTP_SEND: "메일 서버 전송 실패",
  MESSAGE_FORMAT: "메일 형식 오류",
  WORKER_INTERRUPTED: "전송 작업 중단",
  DELIVERY_ERROR: "전송 오류",
};
const alertFilter = ref("all");
const filteredAlerts = computed(() =>
  (alerts.value?.notifications ?? []).filter(
    (a) =>
      alertFilter.value === "all" ||
      a.type === alertFilter.value ||
      (alertFilter.value === "failed" && a.email?.status === "FAILED"),
  ),
);
const rules = computed(() =>
  (["ERROR_RATE", "LATENCY"] as const).map((type) => {
    const latest = alerts.value?.notifications.find((a) => a.type === type);
    const remaining = latest
      ? Math.max(
          0,
          Math.ceil(
            (new Date(latest.createdAt).getTime() +
              (alerts.value?.policy.cooldownSeconds ?? 600) * 1000 -
              now.value) /
              1000,
          ),
        )
      : 0;
    return {
      type,
      title: type === "ERROR_RATE" ? "서버 오류율" : "평균 응답 시간",
      value:
        type === "ERROR_RATE"
          ? live.value?.serverErrorRate
          : live.value?.averageDurationMs,
      threshold:
        type === "ERROR_RATE"
          ? alerts.value?.policy.errorRatePercent
          : alerts.value?.policy.averageDurationMs,
      unit: type === "ERROR_RATE" ? "%" : "ms",
      remaining,
    };
  }),
);
const query = ref("");
const eventFilter = ref("all");
const eventSort = ref("newest");
const routeScope = ref<AnalyticsRoute | null>(null);
const events = computed(() =>
  [...(analytics.value?.events ?? [])]
    .filter((event) => {
      if (
        routeScope.value &&
        (event.route !== routeScope.value.route ||
          event.method !== routeScope.value.method)
      )
        return false;
      if (
        !`${event.method} ${event.route} ${event.eventId}`
          .toLowerCase()
          .includes(query.value.toLowerCase())
      )
        return false;
      return (
        eventFilter.value === "all" ||
        (eventFilter.value === "5xx" && event.statusCode >= 500) ||
        (eventFilter.value === "4xx" &&
          event.statusCode >= 400 &&
          event.statusCode < 500) ||
        (eventFilter.value === "slow" &&
          event.durationMs >= (alerts.value?.policy.averageDurationMs ?? 500))
      );
    })
    .sort((a, b) =>
      eventSort.value === "slowest"
        ? b.durationMs - a.durationMs
        : b.occurredAt.localeCompare(a.occurredAt),
    ),
);
const selectedEvent = ref<RecentEvent | null>(null);
const eventDialog = ref<HTMLDialogElement | null>(null);
function time(value?: string | null, full = false) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("ko-KR", {
    ...(full ? ({ month: "2-digit", day: "2-digit" } as const) : {}),
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZone: "Asia/Seoul",
  }).format(new Date(value));
}
function number(value?: number | null, digits = 0) {
  return value == null
    ? "—"
    : value.toLocaleString("ko-KR", {
        maximumFractionDigits: digits,
        minimumFractionDigits: digits,
      });
}
function title(a: AlertNotification) {
  return a.type === "ERROR_RATE"
    ? "서버 오류율 기준 초과"
    : "평균 응답 시간 기준 초과";
}
function alertValue(a: AlertNotification, value: number) {
  return `${number(value, a.type === "ERROR_RATE" ? 1 : 0)}${a.type === "ERROR_RATE" ? "%" : " ms"}`;
}
function remaining(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
function openRoute(route: AnalyticsRoute) {
  routeScope.value = route;
  query.value = "";
  eventFilter.value = "all";
  location.hash = "requests";
}
async function openEvent(event: RecentEvent) {
  selectedEvent.value = event;
  await nextTick();
  eventDialog.value?.showModal();
}
function resetFilters() {
  query.value = "";
  eventFilter.value = "all";
  routeScope.value = null;
}
async function refresh() {
  const id = ++generation;
  loading.value = true;
  const key = serviceKey.value,
    env = environment.value,
    range = minutes.value;
  const results = await Promise.allSettled([
    getInfrastructure(),
    getServices(),
    getMetrics(key, env),
    getAnalytics(key, env, range),
    getAlerts(key, env),
  ]);
  if (id !== generation) return;
  const [health, serviceList, metrics, data, notifications] = results;
  errors.value = [];
  if (health.status === "fulfilled") infrastructure.value = health.value;
  else {
    infrastructure.value = null;
    errors.value.push("인프라 상태를 확인하지 못했습니다.");
  }
  if (serviceList.status === "fulfilled") services.value = serviceList.value;
  else errors.value.push("서비스 목록을 가져오지 못했습니다.");
  if (metrics.status === "fulfilled") live.value = metrics.value;
  else {
    live.value = null;
    errors.value.push("실시간 지표를 가져오지 못했습니다.");
  }
  if (data.status === "fulfilled") {
    analytics.value = data.value;
    lastChecked.value = new Date();
  } else {
    analytics.value = null;
    errors.value.push("차트와 요청 이력을 가져오지 못했습니다.");
  }
  if (notifications.status === "fulfilled") {
    alerts.value = notifications.value;
    if (seen)
      newAlerts.value += notifications.value.notifications.filter(
        (a) => !seen!.has(a.alertId),
      ).length;
    seen = new Set(notifications.value.notifications.map((a) => a.alertId));
  } else {
    alerts.value = null;
    errors.value.push("알림 상태를 가져오지 못했습니다.");
  }
  loading.value = false;
  nextRefresh.value = Date.now() + 15000;
}
watch([selection, minutes], () => {
  analytics.value = null;
  live.value = null;
  alerts.value = null;
  seen = null;
  newAlerts.value = 0;
  void refresh();
});
watch(selection, resetFilters);
watch(autoRefresh, (value) => {
  if (value) void refresh();
});
onMounted(() => {
  window.addEventListener("hashchange", syncHash);
  void refresh();
  interval = setInterval(() => {
    now.value = Date.now();
    if (autoRefresh.value && !loading.value && now.value >= nextRefresh.value)
      void refresh();
  }, 1000);
});
onUnmounted(() => {
  generation++;
  clearInterval(interval);
  window.removeEventListener("hashchange", syncHash);
});
</script>

<template>
  <div class="app-layout">
    <aside class="sidebar">
      <a class="brand" href="#overview"
        ><span class="brand-mark"
          ><svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M2 13h5l3-8 4 14 3-6h5"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linejoin="round"
            /></svg></span
        ><span>Service Monitor<small>OBSERVABILITY WORKSPACE</small></span></a
      >
      <div class="workspace-card">
        <span class="workspace-avatar">FL</span>
        <div>개인 워크스페이스<small>풋볼라운지 모니터링</small></div>
        <span class="green-dot"></span>
      </div>
      <p class="nav-caption">MONITORING</p>
      <nav aria-label="주 메뉴">
        <a
          v-for="item in pages"
          :key="item.id"
          :href="`#${item.id}`"
          :class="['nav-link', { active: page === item.id }]"
          :aria-current="page === item.id ? 'page' : undefined"
          ><span class="nav-icon"><NavIcon :name="item.id" /></span
          >{{ item.label
          }}<span
            v-if="item.id === 'alerts' && alerts?.notifications.length"
            class="nav-count"
            >{{ alerts.notifications.length }}</span
          ></a
        >
      </nav>
      <div class="sidebar-health">
        <span class="green-dot" :class="{ off: connected !== 3 }"></span
        ><span>인프라 {{ connected }} / 3 연결</span
        ><a href="#infrastructure" aria-label="인프라 상태 확인">↗</a>
      </div>
      <div class="sidebar-foot">
        <span class="env-tag">LOCAL</span
        ><span>내 컴퓨터에서 실행 중<small>Service Monitor · v0.2</small></span>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          <span>워크스페이스</span><i>/</i><span>APM</span><i>/</i
          ><strong>{{ currentPage.label }}</strong>
        </div>
        <div class="topbar-right">
          <span
            class="email-indicator"
            :class="{ connected: alerts?.email.enabled }"
            ><span class="status-dot"></span
            >{{
              alerts?.email.enabled ? "이메일 알림 켜짐" : "이메일 알림 꺼짐"
            }}</span
          ><span class="user-avatar">FL</span>
        </div>
      </header>
      <main>
        <div class="page-heading">
          <div>
            <div class="eyebrow">APPLICATION PERFORMANCE</div>
            <h1>
              {{
                page === "overview"
                  ? (selectedService?.displayName ?? "풋볼라운지")
                  : currentPage.label
              }}<span v-if="page === 'overview'" class="environment">{{
                environment
              }}</span>
            </h1>
            <p>{{ currentPage.hint }}</p>
          </div>
          <div class="page-status">
            <span :class="['status-pill', liveStatus.tone]"
              ><i></i>{{ liveStatus.label }}</span
            ><small>{{ liveStatus.detail }}</small>
          </div>
        </div>
        <div class="filter-bar">
          <div class="service-filter">
            <span class="filter-label">서비스</span
            ><select v-model="selection" aria-label="모니터링 서비스">
              <option v-if="!services.length" :value="selection">
                {{ serviceKey }} · {{ environment }}
              </option>
              <option
                v-for="s in services"
                :key="s.serviceKey + s.environment"
                :value="`${s.serviceKey}|${s.environment}`"
              >
                {{ s.displayName }} · {{ s.environment }}
              </option>
            </select>
          </div>
          <div class="time-controls">
            <div class="segmented" role="group" aria-label="조회 기간">
              <button
                v-for="m in [5, 15, 60]"
                :key="m"
                :class="{ selected: minutes === m }"
                :aria-pressed="minutes === m"
                @click="minutes = m"
              >
                {{ m === 60 ? "1시간" : m + "분" }}
              </button>
            </div>
            <button
              class="auto-button"
              :class="{ paused: !autoRefresh }"
              :aria-pressed="autoRefresh"
              @click="autoRefresh = !autoRefresh"
            >
              <span aria-hidden="true">{{ autoRefresh ? "Ⅱ" : "▷" }}</span
              >{{
                autoRefresh ? `자동 갱신 · ${refreshIn}초` : "갱신 일시정지"
              }}</button
            ><button
              class="icon-button refresh"
              :class="{ spinning: loading }"
              aria-label="상태 새로고침"
              :disabled="loading"
              @click="refresh"
            >
              ↻
            </button>
          </div>
        </div>
        <div v-if="errors.length" role="alert" class="error-banner">
          <strong>일부 데이터를 불러오지 못했습니다.</strong>
          <p>{{ errors.join(" ") }}</p>
          <button class="text-button" @click="refresh">다시 시도 ↻</button>
        </div>
        <div v-if="newAlerts" role="status" class="notice-banner">
          <span
            ><strong>새 알림 {{ newAlerts }}건</strong> · 기준을 초과한 요청이
            감지됐습니다.</span
          ><a href="#alerts" @click="newAlerts = 0">알림 확인 →</a
          ><button
            class="icon-button"
            aria-label="새 알림 안내 닫기"
            @click="newAlerts = 0"
          >
            ×
          </button>
        </div>
        <p v-if="!autoRefresh" class="paused-note">
          자동 갱신이 일시정지되었습니다. 서버의 수집과 이메일 발송은
          계속됩니다.
        </p>

        <template v-if="page === 'overview'">
          <div class="metric-grid">
            <article class="metric-card">
              <div class="metric-label">
                총 요청 <span class="metric-icon mint">↗</span>
              </div>
              <div class="metric-value">
                {{ number(summary?.requestCount) }}<small>건</small>
              </div>
              <div class="metric-meta">
                <span class="mint-text"
                  >{{
                    summary
                      ? number(summary.requestCount / (minutes * 60), 2)
                      : "—"
                  }}
                  req/s</span
                ><span>최근 {{ minutes }}분</span>
              </div>
            </article>
            <article class="metric-card">
              <div class="metric-label">
                서버 오류율 <span class="metric-icon rose">!</span>
              </div>
              <div
                class="metric-value"
                :class="{
                  'danger-text':
                    (summary?.serverErrorRate ?? 0) >=
                    (alerts?.policy.errorRatePercent ?? 10),
                }"
              >
                {{ number(summary?.serverErrorRate, 1) }}<small>%</small>
              </div>
              <div class="metric-meta">
                <span>HTTP 5xx</span
                ><span>{{ number(summary?.serverErrorCount) }}건 발생</span>
              </div>
            </article>
            <article class="metric-card">
              <div class="metric-label">
                평균 응답 시간 <span class="metric-icon blue">◷</span>
              </div>
              <div class="metric-value">
                {{ number(summary?.averageDurationMs) }}<small>ms</small>
              </div>
              <div class="metric-meta">
                <span>서버 처리 시간</span
                ><span>최대 {{ number(summary?.maxDurationMs) }} ms</span>
              </div>
            </article>
            <article class="metric-card">
              <div class="metric-label">
                P95 응답 시간 <span class="metric-icon violet">⌁</span>
              </div>
              <div class="metric-value">
                {{ number(summary?.p95DurationMs) }}<small>ms</small>
              </div>
              <div class="metric-meta">
                <span>요청 95%가 이 시간 이내</span
                ><span
                  class="help-dot"
                  title="선택 기간 전체 요청을 응답 시간순으로 정렬해 95번째 백분위 값을 계산합니다. 구간별 P95의 평균이 아닙니다."
                  >?</span
                >
              </div>
            </article>
          </div>
          <div class="section-heading">
            <h2>트래픽 & 성능</h2>
            <span
              >{{
                analytics
                  ? `${time(analytics.from)} — ${time(analytics.asOf)}`
                  : "데이터 확인 중"
              }}
              · KST</span
            >
          </div>
          <div class="chart-grid-layout">
            <MetricChart
              id="traffic"
              title="요청 처리량"
              subtitle="시간대별 초당 요청 수"
              kind="requests"
              :state="loading ? 'loading' : analytics ? 'ready' : 'error'"
              :buckets="analytics?.buckets ?? []"
              :step="analytics?.bucketSeconds ?? 30"
            /><MetricChart
              id="errors"
              title="서버 오류율"
              subtitle="HTTP 5xx / 전체 요청"
              kind="errors"
              :state="loading ? 'loading' : analytics ? 'ready' : 'error'"
              :buckets="analytics?.buckets ?? []"
              :step="analytics?.bucketSeconds ?? 30"
            /><MetricChart
              id="latency"
              title="응답 시간"
              subtitle="평균과 P95 비교 · ms"
              kind="latency"
              :state="loading ? 'loading' : analytics ? 'ready' : 'error'"
              :buckets="analytics?.buckets ?? []"
              :step="analytics?.bucketSeconds ?? 30"
            />
          </div>
          <div class="overview-bottom">
            <section class="panel">
              <div class="panel-heading">
                <div>
                  <h2>주요 엔드포인트</h2>
                  <p>요청량 상위 50개 중 P95 기준 정렬</p>
                </div>
                <a href="#endpoints" class="text-link">전체 보기 ↗</a>
              </div>
              <RouteTable
                :routes="analytics?.routes ?? []"
                compact
                @select="openRoute"
              />
            </section>
            <section class="panel rules-panel">
              <div class="panel-heading">
                <div>
                  <h2>알림 규칙</h2>
                  <p>
                    최근 5분 · 최소
                    {{ alerts?.policy.minimumRequests ?? "—" }}건
                  </p>
                </div>
                <a href="#alerts" class="text-link">이력 ↗</a>
              </div>
              <div v-for="rule in rules" :key="rule.type" class="rule-summary">
                <div>
                  <strong>{{ rule.title }}</strong
                  ><span
                    >기준 {{ rule.threshold ?? "—" }} {{ rule.unit }} 이상</span
                  >
                </div>
                <b
                  :class="{
                    'danger-text':
                      rule.value != null &&
                      rule.threshold != null &&
                      rule.value >= rule.threshold,
                  }"
                  >{{ number(rule.value, rule.type === "ERROR_RATE" ? 1 : 0) }}
                  <small>{{ rule.unit }}</small></b
                >
                <p v-if="rule.remaining" class="cooldown-label">
                  <span class="status-dot"></span>반복 억제 중
                  <em>{{ remaining(rule.remaining) }}</em>
                </p>
                <p v-else class="muted rule-ready">
                  {{
                    alerts?.policy.enabled
                      ? "다음 기준 초과 시 알림"
                      : "알림 비활성화"
                  }}
                </p>
              </div>
              <div class="channel-summary">
                <span class="channel-icon">✉</span>
                <div>
                  <strong>{{
                    alerts?.email.enabled
                      ? "이메일로 알림 받는 중"
                      : "이메일 알림 꺼짐"
                  }}</strong
                  ><small>{{
                    alerts?.email.recipient ?? "수신 주소 미설정"
                  }}</small>
                </div>
                <span
                  class="green-dot"
                  :class="{ off: !alerts?.email.enabled }"
                ></span>
              </div>
            </section>
          </div>
          <section class="infra-strip" aria-label="인프라 연결 요약">
            <span>수집 파이프라인</span
            ><template v-for="(dep, index) in dependencies" :key="dep.key"
              ><i v-if="index" aria-hidden="true">/</i
              ><a href="#infrastructure"
                ><span
                  class="green-dot"
                  :class="{
                    off: infrastructure?.components?.[dep.key]?.status !== 'UP',
                  }"
                ></span
                >{{ dep.name
                }}<small>{{
                  infrastructure?.components?.[dep.key]?.status === "UP"
                    ? "연결됨"
                    : "확인 필요"
                }}</small></a
              ></template
            ><span class="strip-note">로컬 테스트 환경</span>
          </section>
        </template>

        <section v-if="page === 'endpoints'" class="panel">
          <div class="panel-heading">
            <div>
              <h2>엔드포인트 성능</h2>
              <p>
                최근 {{ minutes }}분 · 요청량 상위 50개 · 행을 선택해 관련 요청
                탐색
              </p>
            </div>
            <span class="count-tag"
              >{{ analytics?.routes.length ?? 0 }}개 API</span
            >
          </div>
          <RouteTable :routes="analytics?.routes ?? []" @select="openRoute" />
        </section>

        <section v-if="page === 'requests'" class="panel requests-panel">
          <div class="panel-heading">
            <div>
              <h2>요청 탐색기</h2>
              <p>
                선택 기간의 최신 100건에서 검색 · 결과 {{ events.length }}건
              </p>
            </div>
            <select v-model="eventSort" aria-label="요청 정렬">
              <option value="newest">최신순</option>
              <option value="slowest">느린 순</option>
            </select>
          </div>
          <div class="request-filters">
            <input
              v-model="query"
              type="search"
              aria-label="요청 검색"
              placeholder="경로 또는 이벤트 ID 검색"
            />
            <div class="filter-chips">
              <button
                v-for="filter in [
                  { id: 'all', label: '전체' },
                  { id: '5xx', label: '5xx 오류' },
                  { id: '4xx', label: '4xx 응답' },
                  {
                    id: 'slow',
                    label: `느린 요청 ≥ ${alerts?.policy.averageDurationMs ?? 500}ms`,
                  },
                ]"
                :key="filter.id"
                :class="{ selected: eventFilter === filter.id }"
                :aria-pressed="eventFilter === filter.id"
                @click="eventFilter = filter.id"
              >
                {{ filter.label }}
              </button>
            </div>
          </div>
          <div v-if="routeScope" class="scope-chip">
            <span class="mono"
              >{{ routeScope.method }} {{ routeScope.route }}</span
            ><button aria-label="API 필터 해제" @click="routeScope = null">
              ×
            </button>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>발생 시각</th>
                  <th>요청 경로</th>
                  <th>응답</th>
                  <th class="numeric">처리 시간</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="event in events" :key="event.eventId">
                  <td class="mono muted nowrap">
                    {{ time(event.occurredAt) }}
                  </td>
                  <td>
                    <span class="method">{{ event.method }}</span>
                    <span class="mono request-path">{{ event.route }}</span>
                  </td>
                  <td>
                    <span
                      :class="[
                        'http-badge',
                        event.statusCode >= 500
                          ? 'danger'
                          : event.statusCode >= 400
                            ? 'amber'
                            : 'green',
                      ]"
                      >{{ event.statusCode }}</span
                    >
                  </td>
                  <td
                    class="numeric mono"
                    :class="{
                      'amber-text':
                        event.durationMs >=
                        (alerts?.policy.averageDurationMs ?? 500),
                    }"
                  >
                    {{ number(event.durationMs) }} <small>ms</small>
                  </td>
                  <td>
                    <button
                      class="text-button"
                      :aria-label="`${event.eventId} 요청 상세 보기`"
                      @click="openEvent(event)"
                    >
                      상세 ↗
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="!events.length" class="empty-state">
            <strong>조건에 맞는 요청이 없습니다.</strong>
            <p>기간을 넓히거나 검색 조건을 바꿔 보세요.</p>
            <button class="text-button" @click="resetFilters">
              검색 조건 초기화
            </button>
          </div>
        </section>

        <template v-if="page === 'alerts'">
          <div class="alert-rule-grid">
            <article
              v-for="rule in rules"
              :key="rule.type"
              class="panel alert-rule"
            >
              <div class="rule-title">
                <span
                  :class="[
                    'metric-icon',
                    rule.type === 'ERROR_RATE' ? 'rose' : 'blue',
                  ]"
                  >{{ rule.type === "ERROR_RATE" ? "!" : "◷" }}</span
                >
                <h2>{{ rule.title }}</h2>
                <span class="count-tag">{{
                  alerts?.policy.enabled ? "활성" : "비활성"
                }}</span>
              </div>
              <p class="rule-threshold">
                {{ rule.threshold ?? "—" }} <small>{{ rule.unit }} 이상</small>
              </p>
              <p class="muted">
                최근 5분 · 최소 {{ alerts?.policy.minimumRequests ?? "—" }}건 ·
                반복 억제 {{ (alerts?.policy.cooldownSeconds ?? 600) / 60 }}분
              </p>
              <div class="rule-footer">
                <span
                  >현재
                  {{ number(rule.value, rule.type === "ERROR_RATE" ? 1 : 0) }}
                  {{ rule.unit }}</span
                ><span :class="rule.remaining ? 'amber-text' : 'muted'">{{
                  rule.remaining
                    ? `반복 억제 ${remaining(rule.remaining)} 남음`
                    : "반복 억제 대기"
                }}</span>
              </div>
            </article>
            <article class="panel email-card">
              <div class="rule-title">
                <span class="metric-icon mint">✉</span>
                <h2>이메일 알림</h2>
              </div>
              <strong>{{
                alerts?.email.enabled ? "발송 활성화" : "발송 비활성화"
              }}</strong>
              <p>{{ alerts?.email.recipient ?? "설정되지 않음" }}</p>
              <div class="email-details">
                실패 시 최대 {{ alerts?.email.maxAttempts ?? 5 }}회 시도<br />대시보드를
                닫아도 발송합니다.
              </div>
            </article>
          </div>
          <section class="panel">
            <div class="panel-heading">
              <div>
                <h2>
                  알림 타임라인
                  <span class="inline-count">{{ filteredAlerts.length }}</span>
                </h2>
                <p>최근 50건 · 조회 기간과 별개인 전체 알림 이력</p>
              </div>
              <select v-model="alertFilter" aria-label="알림 필터">
                <option value="all">전체 알림</option>
                <option value="ERROR_RATE">서버 오류율</option>
                <option value="LATENCY">응답 시간</option>
                <option value="failed">이메일 발송 실패</option>
              </select>
            </div>
            <div v-if="!filteredAlerts.length" class="empty-state">
              표시할 알림이 없습니다.
            </div>
            <article
              v-for="alert in filteredAlerts"
              :key="alert.alertId"
              class="alert-row"
            >
              <span
                :class="[
                  'alert-icon',
                  alert.type === 'ERROR_RATE' ? 'rose' : 'blue',
                ]"
                >{{ alert.type === "ERROR_RATE" ? "!" : "◷" }}</span
              >
              <div class="alert-body">
                <div class="alert-title-line">
                  <h3>{{ title(alert) }}</h3>
                  <time>{{ time(alert.createdAt, true) }}</time>
                </div>
                <p>
                  <strong>{{ alertValue(alert, alert.observedValue) }}</strong>
                  관측
                  <span class="muted"
                    >/ 기준 {{ alertValue(alert, alert.thresholdValue) }}</span
                  >
                </p>
                <div class="alert-row-meta">
                  <span
                    >발생 당시 {{ alert.requestCount }}건 ·
                    {{ alert.windowSeconds / 60 }}분 구간</span
                  ><span class="mono">{{ alert.alertId.slice(0, 8) }}</span>
                </div>
                <div
                  v-if="alert.email?.status === 'RETRY'"
                  class="amber-text small"
                >
                  다음 시도 {{ time(alert.email.nextAttemptAt) }} ·
                  {{
                    emailErrors[alert.email.lastErrorCode ?? ""] ?? "전송 지연"
                  }}
                </div>
                <div
                  v-if="alert.email?.status === 'FAILED'"
                  class="danger-text small"
                >
                  {{
                    emailErrors[alert.email.lastErrorCode ?? ""] ?? "전송 오류"
                  }}
                  · 메일 설정 확인 필요
                </div>
              </div>
              <div class="delivery-state">
                <span
                  v-if="alert.email"
                  :class="['delivery-badge', alert.email.status.toLowerCase()]"
                  ><span>{{ alert.email.status === "SENT" ? "✓" : "·" }}</span>
                  {{ emailLabels[alert.email.status] }}</span
                ><span v-else class="delivery-badge neutral"
                  >이메일 미설정 당시 알림</span
                ><small v-if="alert.email"
                  >{{ alert.email.attempts }} /
                  {{ alerts?.email.maxAttempts }}회 시도<span
                    v-if="alert.email.sentAt"
                  >
                    · {{ time(alert.email.sentAt) }}</span
                  ></small
                >
              </div>
            </article>
            <p class="panel-footnote">
              SMTP 접수는 메일 서버의 수신을 의미합니다. 과거 알림은 현재 장애
              상태를 뜻하지 않습니다.
            </p>
          </section>
        </template>

        <template v-if="page === 'infrastructure'"
          ><div class="infrastructure-grid">
            <article
              v-for="dep in dependencies"
              :key="dep.key"
              class="panel infrastructure-card"
            >
              <div class="rule-title">
                <span class="dependency-icon">{{ dep.icon }}</span
                ><span
                  :class="[
                    'status-pill',
                    infrastructure?.components?.[dep.key]?.status === 'UP'
                      ? 'green'
                      : 'amber',
                  ]"
                  ><i></i
                  >{{
                    infrastructure?.components?.[dep.key]?.status === "UP"
                      ? "연결됨"
                      : "확인 필요"
                  }}</span
                >
              </div>
              <h2>{{ dep.name }}</h2>
              <strong>{{ dep.role }}</strong>
              <p>{{ dep.detail }}</p>
            </article>
          </div>
          <section class="panel service-panel">
            <div class="panel-heading">
              <div>
                <h2>서비스 수집 상태</h2>
                <p>
                  최근 수신은 60초 이내 이벤트 기준이며 서비스 가용성과는
                  다릅니다.
                </p>
              </div>
            </div>
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>서비스</th>
                    <th>환경</th>
                    <th>수집 상태</th>
                    <th>마지막 수신</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="s in services" :key="s.serviceKey + s.environment">
                    <td>
                      <strong>{{ s.displayName }}</strong
                      ><small class="block mono muted">{{
                        s.serviceKey
                      }}</small>
                    </td>
                    <td>
                      <span class="environment">{{ s.environment }}</span>
                    </td>
                    <td>
                      <span
                        :class="[
                          'status-pill',
                          s.collectionStatus === 'RECEIVING'
                            ? 'green'
                            : 'muted',
                        ]"
                        ><i></i
                        >{{
                          s.collectionStatus === "RECEIVING"
                            ? "최근 수신"
                            : s.collectionStatus === "IDLE"
                              ? "수신 이력 있음"
                              : "수집 대기"
                        }}</span
                      >
                    </td>
                    <td class="mono">{{ time(s.lastReceivedAt, true) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <section class="panel pipeline-panel">
            <h2>요청에서 알림까지</h2>
            <div class="pipeline">
              <span>풋볼라운지<small>HTTP 요청 수집</small></span
              ><b>→</b><span>Kafka<small>요청 이벤트 전달</small></span
              ><b>→</b
              ><span>지표 · 이력 · 알림<small>독립적으로 처리</small></span
              ><b>→</b><span>Gmail<small>발송 결과 보관</small></span>
            </div>
            <p class="muted">
              모니터링 PC와 서버가 실행 중일 때 수집과 알림이 동작합니다.
            </p>
          </section></template
        >
        <footer>
          <span
            ><span class="green-dot" :class="{ off: !!errors.length }"></span
            >{{
              loading
                ? "데이터 갱신 중"
                : `마지막 갱신 ${lastChecked ? time(lastChecked.toISOString()) : "—"}`
            }}</span
          ><span>서버 처리 시간 기준 · 로컬 요청 데이터</span>
        </footer>
      </main>
    </div>
    <dialog
      ref="eventDialog"
      class="event-dialog"
      aria-label="요청 상세"
      @click.self="eventDialog?.close()"
      @close="selectedEvent = null"
    >
      <template v-if="selectedEvent"
        ><div class="panel-heading">
          <div>
            <p class="eyebrow">REQUEST DETAIL</p>
            <h2>요청 상세</h2>
          </div>
          <button
            class="icon-button"
            aria-label="요청 상세 닫기"
            @click="eventDialog?.close()"
          >
            ×
          </button>
        </div>
        <div class="event-detail-path">
          <span class="method">{{ selectedEvent.method }}</span
          ><code>{{ selectedEvent.route }}</code>
        </div>
        <dl>
          <dt>HTTP 상태</dt>
          <dd>{{ selectedEvent.statusCode }}</dd>
          <dt>처리 시간</dt>
          <dd>{{ number(selectedEvent.durationMs) }} ms</dd>
          <dt>발생 시각</dt>
          <dd>{{ time(selectedEvent.occurredAt, true) }}</dd>
          <dt>배포 버전</dt>
          <dd class="mono">{{ selectedEvent.deploymentVersion }}</dd>
          <dt>이벤트 ID</dt>
          <dd class="mono">{{ selectedEvent.eventId }}</dd>
        </dl>
        <p class="panel-footnote">
          요청 본문·인증 헤더·쿼리 문자열은 수집하지 않습니다.
        </p></template
      >
    </dialog>
  </div>
</template>
