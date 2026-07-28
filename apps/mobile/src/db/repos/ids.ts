/**
 * Local primary-key generation for the SQLite repositories.
 *
 * Ids are minted on-device rather than by the backend so a row can be written
 * and referenced while offline; the sync worker later maps the local id to the
 * Convex document id via the `convex_id` columns from migration 002.
 *
 * `medications.ts` and `doseLogs.ts` each carry their own copy of this
 * function from the POC. The unified repositories share this one instead of
 * adding a third and fourth copy; the POC copies are left alone because those
 * files are on the rollback path.
 */

/**
 * Returns a RFC 4122 v4 UUID.
 *
 * `crypto.randomUUID()` is deliberately avoided: the Hermes runtime in Expo
 * SDK 56 does not expose it on the global scope in all configurations. This
 * implementation is dependency-free, and the collision risk is negligible for
 * ids scoped to a single device.
 */
export function generateId(): string {
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i += 1) {
    bytes[i] = Math.floor(Math.random() * 256);
  }
  // Per RFC 4122 §4.4: set version (4) and variant (10xx) bits.
  const b6 = bytes[6] ?? 0;
  const b8 = bytes[8] ?? 0;
  bytes[6] = (b6 & 0x0f) | 0x40;
  bytes[8] = (b8 & 0x3f) | 0x80;

  const hex: string[] = [];
  for (let i = 0; i < 16; i += 1) {
    const byte = bytes[i] ?? 0;
    hex.push(byte.toString(16).padStart(2, "0"));
  }
  return `${hex.slice(0, 4).join("")}-${hex.slice(4, 6).join("")}-${hex.slice(6, 8).join("")}-${hex.slice(8, 10).join("")}-${hex.slice(10, 16).join("")}`;
}
