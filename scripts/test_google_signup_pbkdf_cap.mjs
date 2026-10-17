// Reproduce Cloudflare Workers' WebCrypto PBKDF2 iteration limit in Node.
// test_backend.mjs includes Google signup, regular login and reset/change flows.
import { webcrypto } from 'node:crypto';
const original = globalThis.crypto || webcrypto;
const subtle = original.subtle;
const limitSubtle = new Proxy(subtle, {
  get(target, name) {
    if (name === 'deriveBits') {
      return async (alg, key, bits) => {
        if (alg?.name === 'PBKDF2' && alg.iterations > 100000)
          throw Error(`Pbkdf2 failed: iteration counts above 100000 are not supported (requested ${alg.iterations}).`);
        return target.deriveBits(alg, key, bits);
      };
    }
    const method = Reflect.get(target, name, target);
    return typeof method === 'function' ? method.bind(target) : method;
  }
});
Object.defineProperty(globalThis, 'crypto', {
  value: { getRandomValues: original.getRandomValues.bind(original), randomUUID: original.randomUUID.bind(original), subtle: limitSubtle },
  configurable: true
});
await import('./test_backend.mjs');
