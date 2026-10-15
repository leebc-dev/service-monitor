export type HealthStatus = "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN";
export interface InfrastructureHealth {
  status: HealthStatus;
  components?: Record<string, { status: HealthStatus }>;
}
export interface MonitoredService {
  serviceKey: string;
  displayName: string;
  environment: string;
  collectionStatus: "NOT_CONNECTED" | "RECEIVING" | "IDLE";
  lastReceivedAt: string | null;
}

export interface RequestMetrics {
  windowSeconds: number;
  asOf: string;
  requestCount: number;
  serverErrorCount: number;
  serverErrorRate: number | null;
  averageDurationMs: number | null;
  routes: {
    route: string;
    requestCount: number;
    serverErrorCount: number;
    averageDurationMs: number;
  }[];
}
export interface RecentEvent {
  eventId: string;
  method: string;
  route: string;
  statusCode: number;
  durationMs: number;
  occurredAt: string;
  deploymentVersion: string;
}

export interface AlertNotification {
  alertId: string;
  type: "ERROR_RATE" | "LATENCY";
  observedValue: number;
  thresholdValue: number;
  requestCount: number;
  windowSeconds: number;
  windowEndedAt: string;
  createdAt: string;
  email: EmailDeliveryStatus | null;
}

export interface EmailDeliveryStatus {
  status: "PENDING" | "SENDING" | "RETRY" | "SENT" | "FAILED";
  attempts: number;
  nextAttemptAt: string;
  sentAt: string | null;
  lastErrorCode: string | null;
}

export interface AlertsResponse {
  email: {
    enabled: boolean;
    configured: boolean;
    recipient: string | null;
    maxAttempts: number;
  };
  policy: {
    enabled: boolean;
    minimumRequests: number;
    errorRatePercent: number;
    averageDurationMs: number;
    cooldownSeconds: number;
  };
  notifications: AlertNotification[];
}

async function getJson<T>(path: string, acceptUnavailable = false): Promise<T> {
  const response = await fetch(path, {
    cache: "no-store",
    signal: AbortSignal.timeout(8000),
    headers: { Accept: "application/json" },
  });
  if (!response.ok && !(acceptUnavailable && response.status === 503)) {
    throw new Error(`Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export const getServices = () =>
  getJson<MonitoredService[]>("/api/v1/services");
export const getInfrastructure = () =>
  getJson<InfrastructureHealth>("/actuator/health/infrastructure", true);
const servicePath = (service: string, environment: string, resource: string) =>
  `/api/v1/services/${encodeURIComponent(service)}/${resource}?environment=${encodeURIComponent(environment)}`;
export const getMetrics = (
  service = "football-lounge",
  environment = "development",
) => getJson<RequestMetrics>(servicePath(service, environment, "metrics"));
export const getRecentEvents = () =>
  getJson<RecentEvent[]>("/api/v1/services/football-lounge/events");
export const getAlerts = (
  service = "football-lounge",
  environment = "development",
) => getJson<AlertsResponse>(servicePath(service, environment, "alerts"));

export interface AnalyticsSummary {
  requestCount: number;
  serverErrorCount: number;
  serverErrorRate: number | null;
  averageDurationMs: number | null;
  p95DurationMs: number | null;
  maxDurationMs: number | null;
}
export interface AnalyticsRoute {
  method: string;
  route: string;
  metrics: AnalyticsSummary;
}
export interface AnalyticsBucket {
  startedAt: string;
  metrics: AnalyticsSummary;
}
export interface AnalyticsResponse {
  windowMinutes: number;
  bucketSeconds: number;
  from: string;
  asOf: string;
  summary: AnalyticsSummary;
  buckets: AnalyticsBucket[];
  routes: AnalyticsRoute[];
  events: RecentEvent[];
}
export const getAnalytics = (
  service: string,
  environment: string,
  minutes: number,
) =>
  getJson<AnalyticsResponse>(
    servicePath(service, environment, "analytics") + `&minutes=${minutes}`,
  );
