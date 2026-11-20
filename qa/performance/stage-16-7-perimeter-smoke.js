import http from 'k6';
import { check, sleep } from 'k6';
const target = __ENV.WMS_K6_TARGET || 'http://127.0.0.1:8081/api/wms/v1/tenants/current/warehouses';
export const options = { vus: 2, duration: '20s', thresholds: { checks: ['rate==1'] } };
export default function () {
  const response = http.get(target, { timeout: '5s' });
  check(response, { 'protected endpoint denies unauthenticated request (403)': (r) => r.status === 403 });
  sleep(1);
}
