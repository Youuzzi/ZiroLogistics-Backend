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
  thresholds: {
    checks: ["rate==1"],
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000", "p(99)<2000"],
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
}
