import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 5,
  duration: '30s',
};

const BASE_URL = 'http://localhost:8081';
// Если запускаешь k6 из WSL напрямую — используй http://localhost:8081
// Если в Docker — host.docker.internal

const codes = ['USD', 'EUR', 'KRW', 'CNY', 'JPY'];

export default function () {
  const vu = __VU;   // номер виртуального пользователя
  const iter = __ITER; // номер итерации

  const params = {
    headers: {
      'X-Client': 'k6',
      'X-User': `k6-vu-${vu}`,
    },
  };

  // Случайная валюта
  const code = codes[Math.floor(Math.random() * codes.length)];

  // 70% — одиночная валюта, 30% — все курсы
  if (Math.random() < 0.7) {
    const res = http.get(`${BASE_URL}/api/rates/${code}`, params);
    check(res, {
      'status 200': (r) => r.status === 200,
      'status 429': (r) => r.status === 429,
    });
  } else {
    const res = http.get(`${BASE_URL}/api/rates`, params);
    check(res, {
      'status 200': (r) => r.status === 200,
      'status 429': (r) => r.status === 429,
    });
  }

  sleep(0.2 + Math.random() * 0.3);
}
