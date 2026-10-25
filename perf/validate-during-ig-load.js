// k6: validation latency and probe latency while an IG is uploaded and included (SC-004).
//   k6 run -e BASE=http://127.0.0.1:8880 -e ADMIN_KEY=... perf/validate-during-ig-load.js
// Compare p(95) of `validate` with a run of perf/validate.js: it must rise by no more than 20%.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8880';
const AUTH = __ENV.ADMIN_KEY ? { Authorization: `Bearer ${__ENV.ADMIN_KEY}` } : {};
const patient = open('../src/test/resources/fhir/resources/dk/Patient.json');
const usCore = open('../src/test/resources/ig/packages/hl7/fhir/us/core/hl7.fhir.us.core-7.0.0.tgz', 'b');

export const options = {
  scenarios: {
    validate: { executor: 'constant-vus', vus: Number(__ENV.VUS || 8), duration: '90s', exec: 'validate' },
    probes: { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: '90s', preAllocatedVUs: 4, exec: 'probe' },
    igLoad: { executor: 'per-vu-iterations', vus: 1, iterations: 1, startTime: '30s', exec: 'loadIg' },
  },
  thresholds: {
    'http_req_duration{name:readiness}': ['max<1000'],
    'http_req_duration{name:liveness}': ['max<1000'],
    'http_req_failed{name:validate}': ['rate<0.001'],
  },
};

export function validate() {
  const r = http.post(`${BASE}/R4/validate`, patient, {
    headers: { 'Content-Type': 'application/fhir+json', Accept: 'application/json' },
    tags: { name: 'validate' },
  });
  check(r, { 'validate 200': (res) => res.status === 200 });
}

export function probe() {
  http.get(`${BASE}/health/liveness`, { tags: { name: 'liveness' } });
  http.get(`${BASE}/health/readiness`, { tags: { name: 'readiness' } });
}

export function loadIg() {
  const up = http.post(`${BASE}/igs/upload`, { file: http.file(usCore, 'us-core.tgz', 'application/gzip') }, { headers: AUTH, tags: { name: 'upload' } });
  check(up, { 'upload 200': (res) => res.status === 200 });
  const inc = http.post(`${BASE}/R4/include-ig`, JSON.stringify({ igPackageId: 'hl7.fhir.us.core', igPackageVersion: '7.0.0' }),
    { headers: Object.assign({ 'Content-Type': 'application/json' }, AUTH), tags: { name: 'include' } });
  check(inc, { 'include 200': (res) => res.status === 200 });
}
