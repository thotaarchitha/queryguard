// Admin API under load: 200 virtual users, mostly reads, some dry runs.
//
//   docker run --rm -i --network queryguard_default -e API=http://admin-api:8080 grafana/k6:0.54.0 run - < bench/k6/admin-api.js
//
// THINK_S=0 turns it into a saturation test (max requests/s; latency then just reflects
// queueing). The default 0.5-1.5 s pause per user is closer to people clicking around.
//
// Dry runs are the expensive endpoint: each one parses SQL, loads the policy from the config DB
// and may run EXPLAIN against the shop database.
import http from 'k6/http';
import { check, sleep } from 'k6';

const API = __ENV.API || 'http://localhost:8080';
const THINK = __ENV.THINK_S === undefined ? 1 : Number(__ENV.THINK_S);

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 200 },
        { duration: '2m', target: 200 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:list}': ['p(95)<300'],
    'http_req_duration{endpoint:dryrun}': ['p(95)<800'],
  },
};

const SQL = [
  ['bob', 'SELECT id, status FROM orders WHERE id = 42'],
  ['bob', 'DELETE FROM orders'],
  ['alice', 'SELECT country, count(*) FROM customers GROUP BY country'],
  ['alice', 'SELECT upper(email) FROM customers WHERE id = 1'],
  ['etl_user', 'SELECT * FROM products'],
];

export function setup() {
  const r = http.post(`${API}/auth/token`, JSON.stringify({ username: 'viewer', password: 'viewer-dev' }),
    { headers: { 'Content-Type': 'application/json' } });
  return { token: r.json('access_token') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' };
  if (Math.random() < 0.7) {
    const r = http.get(`${API}/rules`, { headers, tags: { endpoint: 'list' } });
    check(r, { 'list 200': (x) => x.status === 200 });
  } else {
    const [user, sql] = SQL[Math.floor(Math.random() * SQL.length)];
    const r = http.post(`${API}/rules/test`, JSON.stringify({ user, sql }), { headers, tags: { endpoint: 'dryrun' } });
    check(r, { 'dryrun 200': (x) => x.status === 200 });
  }
  if (THINK > 0) {
    sleep(THINK * (0.5 + Math.random()));
  }
}
