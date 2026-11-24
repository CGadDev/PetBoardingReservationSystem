import http from 'k6/http';
import { check } from 'k6';

// Use the same workload for baseline and cached testing.
export const options = {

    scenarios: {

        availability: {

            executor: 'per-vu-iterations',
            vus: 10,
            iterations: 500,
            maxDuration: '3m'
        }
    },

    thresholds: {

        http_req_failed: ['rate==0'],
        checks: ['rate==1']
    },

    summaryTrendStats: [
        'avg',
        'min',
        'med',
        'max',
        'p(90)',
        'p(95)',
        'p(99)'
    ]
};

const BASE_URL =
    __ENV.BASE_URL || 'http://localhost:8082';

export default function () {

    // Retrieve boarding availability.
    const response = http.get(
        `${BASE_URL}/resources`
    );

    // Verify response status and expected boarding capacity.
    check(response, {

        'HTTP status is 200': (r) =>
            r.status === 200,

        'Dog and cat capacity is correct': (r) => {

            try {

                const data = r.json();

                return data.DOG === 0 &&
                       data.CAT === 0;

            } catch (e) {

                return false;
            }
        }
    });
}