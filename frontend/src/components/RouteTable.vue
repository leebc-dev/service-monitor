<script setup lang="ts">
import { computed, ref } from "vue";
import type { AnalyticsRoute } from "../api";
const props = defineProps<{ routes: AnalyticsRoute[]; compact?: boolean }>();
const emit = defineEmits<{ select: [route: AnalyticsRoute] }>();
const search = ref("");
const sort = ref("p95DurationMs");
const rows = computed(() =>
  [...props.routes]
    .filter((r) =>
      `${r.method} ${r.route}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
    )
    .sort(
      (a, b) =>
        (b.metrics[sort.value as "p95DurationMs"] ?? 0) -
          (a.metrics[sort.value as "p95DurationMs"] ?? 0) ||
        a.route.localeCompare(b.route),
    )
    .slice(0, props.compact ? 5 : 50),
);
const maxDuration = computed(() =>
  Math.max(1, ...props.routes.map((r) => r.metrics.p95DurationMs ?? 0)),
);
</script>
<template>
  <div class="table-toolbar">
    <input
      v-if="!compact"
      v-model="search"
      type="search"
      placeholder="API 경로 검색"
      aria-label="API 경로 검색"
    /><span v-else class="muted">느린 요청부터 살펴보세요</span
    ><select v-model="sort" aria-label="API 정렬">
      <option value="p95DurationMs">P95 높은 순</option>
      <option value="requestCount">요청 많은 순</option>
      <option value="serverErrorRate">오류율 높은 순</option>
    </select>
  </div>
  <div class="table-scroll">
    <table>
      <thead>
        <tr>
          <th scope="col">엔드포인트</th>
          <th scope="col" class="numeric">요청</th>
          <th scope="col" class="numeric">오류율</th>
          <th scope="col" class="numeric">평균</th>
          <th scope="col" class="numeric">P95</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="route in rows" :key="route.method + route.route">
          <td>
            <button class="route-link" @click="emit('select', route)">
              <span class="method">{{ route.method }}</span
              ><span class="mono route-path">{{ route.route }}</span
              ><span aria-hidden="true">↗</span>
            </button>
          </td>
          <td class="numeric mono">
            {{ route.metrics.requestCount.toLocaleString() }}
          </td>
          <td
            class="numeric mono"
            :class="{ 'danger-text': (route.metrics.serverErrorRate ?? 0) > 0 }"
          >
            {{ route.metrics.serverErrorRate?.toFixed(1) }}%
          </td>
          <td class="numeric mono">
            {{ route.metrics.averageDurationMs?.toFixed(0) }} <small>ms</small>
          </td>
          <td class="numeric mono latency-cell">
            <span
              >{{ route.metrics.p95DurationMs?.toFixed(0) }}
              <small>ms</small></span
            ><i
              :style="{
                width: `${((route.metrics.p95DurationMs ?? 0) / maxDuration) * 100}%`,
              }"
            ></i>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
  <p v-if="!rows.length" class="empty-state">
    {{
      search
        ? "검색 조건에 맞는 API가 없습니다."
        : "선택한 기간에 수집된 요청이 없습니다."
    }}
  </p>
</template>
