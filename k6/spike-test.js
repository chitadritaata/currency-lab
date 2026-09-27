import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '20s', target: 5 },    // разогрев
    { duration: '20s', target: 30 },   // рост
    { duration: '1m',  target: 100 },  // спайк
    { duration: '30s', target: 100 },  // держим
    { duration: '20s', target: 0 },    // сброс
  ],
};

const BASE_URL = 'http://localhost:8081';
const codes = ['USD', 'EUR', 'KRW', 'CNY', 'JPY'];

export default function () {
  const params = {
    headers: {
      'X-Client': 'k6',
      'X-User': `k6-vu-${__VU}`,
    },
  };

  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`${BASE_URL}/api/rates/${code}`, params);

  check(res, {
    'ok': (r) => r.status === 200 || r.status === 429,
  });

  sleep(0.05);
}
