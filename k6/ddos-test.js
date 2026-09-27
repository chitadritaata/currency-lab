import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 300 },
    { duration: '30s', target: 300 },
    { duration: '10s', target: 0 },
  ],
};

const BASE_URL = 'http://localhost:8081';

export default function () {
  const params = {
    headers: {
      'X-Client': 'k6',
      'X-User': `k6-vu-${__VU}`,
    },
  };

  const res = http.get(`${BASE_URL}/api/rates/USD`, params);

  check(res, {
    'ok': (r) => r.status === 200 || r.status === 429,
  });

  sleep(0.01);
}
