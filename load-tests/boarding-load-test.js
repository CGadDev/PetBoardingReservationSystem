import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 1,
    duration: '30s'
};

export default function () {
    const response = http.get('http://localhost:8082/resources');

    check(response, {
        'HTTP status is 200': (r) => r.status === 200,
        'Response contains DOG and CAT': (r) => {
            try {
                const data = r.json();
                return typeof data.DOG === 'number' &&
                       typeof data.CAT === 'number';
            } catch (e) {
                return false;
            }
        }
    });

	sleep(Number(__ENV.PAUSE ?? '0.5'));
}
