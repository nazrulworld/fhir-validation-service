// k6 load test: validation in a terminology context (spec 007 SC-008, research R15).
//
// Setup (see perf/README.md, "Terminology"): an IG included with {"terminology": {"mode": "ecosystem"}} (or "server"),
// whose profile binds Observation.code to a value set that includes LOINC, and a terminology server reachable from the
// service. Two phases:
//   cached:   the same LOINC code every time; after the first request every answer comes from the code cache;
//   uncached: a different LOINC code every iteration, so every request makes one server round trip.
//
//   k6 run -e BASE=http://127.0.0.1:8880 -e IG='test.tx.ig#1.0.0' -e VUS=8 -e DURATION=60s perf/validate-terminology.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8880';
// Spec 008: validation always needs a VALIDATE or ADMIN key.
const KEY_HEADER = __ENV.FV_KEY ? { Authorization: `Bearer ${__ENV.FV_KEY}` } : {};
const IG = __ENV.IG || 'test.tx.ig#1.0.0';
const PROFILE = __ENV.PROFILE || 'http://example.org/fhir/StructureDefinition/tx-observation';
const TARGET = `${BASE}/R4/validate?ig=${encodeURIComponent(IG)}&profile=${encodeURIComponent(PROFILE)}`;
const DURATION = __ENV.DURATION || '60s';
const VUS = Number(__ENV.VUS || 8);

export const options = {
  scenarios: {
    cached: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'cached', tags: { phase: 'cached' } },
    // Each uncached iteration makes one call to a real registry server (tx.fhir.org): keep it small
    // (UNCACHED_VUS / UNCACHED_DURATION) so the run never load-tests a public server.
    uncached: { executor: 'constant-vus', vus: Number(__ENV.UNCACHED_VUS || 1), duration: __ENV.UNCACHED_DURATION || '10s',
      exec: 'uncached', startTime: DURATION, tags: { phase: 'uncached' } },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    // SC-008: a cached code adds no measurable latency to the 200 ms target.
    'http_req_duration{phase:cached}': ['p(95)<200'],
    // Reported for the record: one terminology round trip each, bounded by FV_TX_TIMEOUT_MS / FV_TX_REQUEST_BUDGET_MS.
    'http_req_duration{phase:uncached}': ['p(95)<10000'],
    'http_reqs{phase:uncached}': ['count>=0'],
  },
};

function observation(code) {
  return JSON.stringify({
    resourceType: 'Observation', status: 'final',
    code: { coding: [{ system: 'http://loinc.org', code: code }] },
  });
}

function post(body) {
  const r = http.post(TARGET, body, { headers: Object.assign({ 'Content-Type': 'application/fhir+json', Accept: 'application/json' }, KEY_HEADER) });
  check(r, { 'status 200': (res) => res.status === 200 });
}

export function cached() {
  post(observation('8867-4'));
}

export function uncached() {
  // A distinct (mostly unknown) code per iteration: one terminology round trip each.
  post(observation(`${__VU}${__ITER}-0`));
}
