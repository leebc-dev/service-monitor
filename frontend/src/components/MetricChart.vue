<script setup lang="ts">
import { computed, ref } from "vue";
import type { AnalyticsBucket } from "../api";
const props = defineProps<{
  id: string;
  title: string;
  subtitle: string;
  buckets: AnalyticsBucket[];
  step: number;
  state: "loading" | "ready" | "error";
  kind: "requests" | "errors" | "latency";
}>();
const active = ref<number | null>(null);
const width = 480,
  height = 190,
  left = 45,
  right = 14,
  top = 16,
  bottom = 32;
const plotWidth = width - left - right,
  plotHeight = height - top - bottom;
const series = computed(() =>
  props.kind === "requests"
    ? [
        {
          name: "초당 요청",
          color: "#6ee7c1",
          values: props.buckets.map((b) => b.metrics.requestCount / props.step),
        },
      ]
    : props.kind === "errors"
      ? [
          {
            name: "서버 오류율",
            color: "#fb8c9b",
            values: props.buckets.map((b) => b.metrics.serverErrorRate),
          },
        ]
      : [
          {
            name: "평균",
            color: "#82b3ff",
            values: props.buckets.map((b) => b.metrics.averageDurationMs),
          },
          {
            name: "P95",
            color: "#c0a0ff",
            values: props.buckets.map((b) => b.metrics.p95DurationMs),
          },
        ],
);
const max = computed(() => {
  const highest = Math.max(
    0,
    ...series.value.flatMap((s) => s.values.map((v) => v ?? 0)),
  );
  if (props.kind === "errors")
    return Math.min(100, Math.max(10, Math.ceil(highest / 20) * 20));
  if (!highest) return props.kind === "latency" ? 100 : 1;
  const magnitude = 10 ** Math.floor(Math.log10(highest));
  return Math.ceil(highest / magnitude / 0.5) * magnitude * 0.5;
});
const hasRequests = computed(() =>
  props.buckets.some((b) => b.metrics.requestCount > 0),
);
const x = (i: number) =>
  left + ((i + 0.5) / Math.max(1, props.buckets.length)) * plotWidth;
const y = (v: number) => top + plotHeight * (1 - v / max.value);
function path(values: (number | null)[]) {
  let previous = false;
  return values
    .map((value, index) => {
      if (value == null) {
        previous = false;
        return "";
      }
      const segment = `${previous ? "L" : "M"}${x(index).toFixed(2)},${y(value).toFixed(2)}`;
      previous = true;
      return segment;
    })
    .join(" ");
}
const labels = computed(() =>
  [
    ...new Set([
      0,
      Math.floor(props.buckets.length / 2),
      props.buckets.length - 1,
    ]),
  ].filter((i) => i >= 0),
);
function time(value: string) {
  return new Date(value).toLocaleTimeString("ko-KR", {
    timeZone: "Asia/Seoul",
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: props.step < 60 ? "2-digit" : undefined,
  });
}
function formatted(value: number | null) {
  if (value == null) return "수집 없음";
  return props.kind === "latency"
    ? `${Math.round(value).toLocaleString()} ms`
    : props.kind === "errors"
      ? `${value.toFixed(1)}%`
      : `${value.toFixed(2)} req/s`;
}
function move(event: MouseEvent) {
  const bounds =
    event.currentTarget instanceof SVGElement
      ? event.currentTarget.getBoundingClientRect()
      : null;
  if (!bounds || !props.buckets.length) return;
  active.value = Math.max(
    0,
    Math.min(
      props.buckets.length - 1,
      Math.floor(
        ((((event.clientX - bounds.left) / bounds.width) * width - left) /
          plotWidth) *
          props.buckets.length,
      ),
    ),
  );
}
function key(event: KeyboardEvent) {
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault();
  active.value = Math.max(
    0,
    Math.min(
      props.buckets.length - 1,
      (active.value ?? props.buckets.length - 1) +
        (event.key === "ArrowLeft" ? -1 : 1),
    ),
  );
}
</script>

<template>
  <section class="panel chart-panel" :aria-labelledby="id + '-title'">
    <div class="chart-heading">
      <div>
        <h2 :id="id + '-title'">{{ title }}</h2>
        <p>{{ subtitle }}</p>
      </div>
      <span class="chart-symbol" aria-hidden="true">↗</span>
    </div>
    <div class="chart-canvas">
      <svg
        :viewBox="`0 0 ${width} ${height}`"
        role="img"
        tabindex="0"
        :aria-label="`${title}. ${hasRequests ? '좌우 화살표로 구간별 값을 확인하세요.' : '선택한 기간에 수집된 요청이 없습니다.'}`"
        @mousemove="move"
        @mouseleave="active = null"
        @focus="active = buckets.length - 1"
        @blur="active = null"
        @keydown="key"
      >
        <g v-for="tick in [0, 1, 2, 3, 4]" :key="tick">
          <line
            :x1="left"
            :x2="width - right"
            :y1="y((max * tick) / 4)"
            :y2="y((max * tick) / 4)"
            class="chart-grid"
          />
          <text
            :x="left - 8"
            :y="y((max * tick) / 4) + 4"
            text-anchor="end"
            class="axis-label"
          >
            {{
              max < 2
                ? ((max * tick) / 4).toFixed(2)
                : Math.round((max * tick) / 4).toLocaleString()
            }}
          </text>
        </g>
        <g v-for="(line, index) in series" :key="line.name">
          <path
            :d="path(line.values)"
            fill="none"
            :stroke="line.color"
            stroke-width="2.3"
            stroke-linejoin="round"
            :stroke-dasharray="index === 1 ? '4 3' : undefined"
          />
          <template v-for="(value, i) in line.values" :key="i">
            <circle
              v-if="value != null && (value > 0 || kind !== 'requests')"
              :cx="x(i)"
              :cy="y(value)"
              r="2.5"
              :fill="line.color"
            />
          </template>
        </g>
        <text
          v-for="i in labels"
          :key="i"
          :x="x(i)"
          :y="height - 7"
          :text-anchor="
            i === 0 ? 'start' : i === buckets.length - 1 ? 'end' : 'middle'
          "
          class="axis-label"
        >
          {{ buckets[i] ? time(buckets[i]!.startedAt) : "" }}
        </text>
        <line
          v-if="active != null && buckets[active]"
          :x1="x(active)"
          :x2="x(active)"
          :y1="top"
          :y2="top + plotHeight"
          class="chart-crosshair"
        />
      </svg>
      <div v-if="!hasRequests" class="chart-empty">
        {{
          state === "loading"
            ? "데이터 확인 중…"
            : state === "error"
              ? "데이터 조회 실패"
              : "수집된 요청 없음"
        }}
      </div>
      <div
        v-if="active != null && buckets[active]"
        class="chart-tooltip"
        role="status"
      >
        <strong>{{ time(buckets[active]!.startedAt) }}부터 {{ step }}초</strong>
        <span v-for="line in series" :key="line.name"
          ><i :style="{ background: line.color }"></i>{{ line.name }}
          <b>{{ formatted(line.values[active] ?? null) }}</b></span
        >
        <small>요청 {{ buckets[active]!.metrics.requestCount }}건</small>
      </div>
    </div>
    <div class="chart-legend">
      <span v-for="line in series" :key="line.name"
        ><i :style="{ background: line.color }"></i>{{ line.name }}</span
      ><small>{{ step }}초 구간</small>
    </div>
  </section>
</template>
