// k6 load test: single-resource validation (SC-003).
//   k6 run -e BASE=http://127.0.0.1:8880 -e VUS=8 -e DURATION=60s perf/validate.js
// Optional IG=name#version validates against that IG (?ig=, URL-encoded); it must be stored (and ideally included).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8880';
// Spec 008: validation always needs a VALIDATE or ADMIN key.
const KEY_HEADER = __ENV.FV_KEY ? { Authorization: `Bearer ${__ENV.FV_KEY}` } : {};
const TARGET = __ENV.IG ? `${BASE}/R4/validate?ig=${encodeURIComponent(__ENV.IG)}` : `${BASE}/R4/validate`;
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
  const r = http.post(TARGET, patient, {
    headers: Object.assign({ 'Content-Type': 'application/fhir+json', Accept: 'application/json' }, KEY_HEADER),
    tags: { name: 'validate' },
  });
  check(r, { 'status 200': (res) => res.status === 200 });
}
