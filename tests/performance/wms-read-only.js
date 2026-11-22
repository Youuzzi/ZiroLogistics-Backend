import http from "k6/http";
import { check, sleep } from "k6";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:8081";
const token = __ENV.WMS_TOKEN;
const warehousePublicId = __ENV.WMS_WAREHOUSE_PUBLIC_ID;

export const options = {
  scenarios: {
    read_only_wms: {
      executor: "constant-vus",
      vus: 5,
      duration: "30s",
    },
  },
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)"],
  thresholds: {
    checks: ["rate>0.99"],
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

const headers = {
  Authorization: `Bearer ${token}`,
  Accept: "application/json",
};

function getAndCheck(name, path) {
  const response = http.get(`${baseUrl}${path}`, {
    headers,
    timeout: "5s",
    tags: { endpoint: name },
  });
  check(response, {
    [`${name} returns HTTP 200`]: (r) => r.status === 200,
  });
}

export default function () {
  getAndCheck("tenant_profile", "/api/wms/v1/tenants/current/profile");
  getAndCheck("warehouse_list", "/api/wms/v1/tenants/current/warehouses");
  getAndCheck(
    "warehouse_detail",
    `/api/wms/v1/tenants/current/warehouses/${warehousePublicId}`,
  );
  sleep(1);
}
