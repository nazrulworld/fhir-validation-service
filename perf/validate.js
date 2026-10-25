// k6 load test: single-resource validation (SC-003).
//   k6 run -e BASE=http://127.0.0.1:8880 -e VUS=8 -e DURATION=60s perf/validate.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8880';
const patient = open('../src/test/resources/fhir/resources/dk/Patient.json');

export const options = {
  vus: Number(__ENV.VUS || 8),
  duration: __ENV.DURATION || '60s',
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{name:validate}': ['p(95)<200'],
  },
};

export default function () {
  const r = http.post(`${BASE}/R4/validate`, patient, {
    headers: { 'Content-Type': 'application/fhir+json', Accept: 'application/json' },
    tags: { name: 'validate' },
  });
  check(r, { 'status 200': (res) => res.status === 200 });
}
