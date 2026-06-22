/**
 * Local SQLite repository for dose logs.
 *
 * Stub for Day 3 — the full implementation lands on Day 5 (per the POC
 * plan §7 Day 5 Agent A). The stub exists so other modules can import
 * the file path and the type surface without breaking the build.
 */

export interface CreateDoseLogInput {
  readonly userId: string;
  readonly medicationId: string;
  readonly scheduledTime: number;
}

/**
 * Throws because dose log creation is not yet implemented.
 *
 * Day 5 will replace this with a real implementation that:
 *   - inserts into `dose_logs` with `status = 'pending'`
 *   - enqueues a `create` operation to `sync_queue`
 *   - returns the inserted row in `@taskrx/shared` `DoseLog` shape
 */
// eslint-disable-next-line @typescript-eslint/require-await
export async function create(_input: CreateDoseLogInput): Promise<never> {
  void _input;
  throw new Error("doseLogs.create: not yet implemented (Day 5)");
}
