import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The permission gate: the path that actually asks a person whether this app
 * may notify them.
 *
 * Issue #20 is the reason it exists. `requestPermissions()` had exactly one
 * caller in the whole app — a debug screen — so a fresh install armed its
 * alarms, fired them on time, and watched Android discard every notification.
 * Nothing failed; nothing was logged; the person simply missed doses. The rules
 * about WHEN to ask and WHAT to say live in `@taskrx/shared` and are pinned
 * there. What is pinned here is the part that only exists on the device: the
 * state actually comes from a platform query, and it is re-read every time the
 * app is foregrounded.
 *
 * ## Why this asserts on collaborators in one place
 *
 * `docs/specs/fused-timeline.md` § "What makes a good test here" rules out
 * asserting which collaborator was called. `applyPermissionRemedy` is the same
 * documented exception `scheduler.test.ts` argues: opening a system settings
 * screen has no return value and no observable effect inside the process, so
 * "which screen opened" IS the behaviour, and routing an exact-alarm gap to the
 * notification settings would be a dead end presented as a fix.
 *
 * Everything else below is asserted as an outcome. In particular "it prompted"
 * is never a call count — the two platform reads return DIFFERENT answers, so a
 * state that changed can only have come from the prompt.
 */

const mocks = vi.hoisted(() => ({
  checkNotificationPermission: vi.fn(),
  requestPermissions: vi.fn(),
  openNotificationSettings: vi.fn<() => Promise<void>>(),
  openExactAlarmSettings: vi.fn<() => Promise<void>>(),
}));

vi.mock("./permissions", () => ({
  checkNotificationPermission: mocks.checkNotificationPermission,
  requestPermissions: mocks.requestPermissions,
  openNotificationSettings: mocks.openNotificationSettings,
  openExactAlarmSettings: mocks.openExactAlarmSettings,
}));

const { applyPermissionRemedy, syncNotificationPermission } =
  await import("./permission-gate");
type PermissionGateState = Awaited<
  ReturnType<typeof syncNotificationPermission>
>;

/** The four platform answers, spelled once. */
const NEVER_ASKED = {
  status: "undetermined",
  canAskAgain: true,
  exactAlarmGranted: true,
  notificationGranted: false,
} as const;
const GRANTED = {
  status: "authorized",
  canAskAgain: false,
  exactAlarmGranted: true,
  notificationGranted: true,
} as const;
const REFUSED = {
  status: "denied",
  canAskAgain: false,
  exactAlarmGranted: true,
  notificationGranted: false,
} as const;
const NO_EXACT_ALARM = {
  status: "partial",
  canAskAgain: false,
  exactAlarmGranted: false,
  notificationGranted: true,
} as const;

beforeEach(() => {
  vi.clearAllMocks();
  // Deliberately unset: a gate that answered without reading the platform would
  // otherwise pass on a default. Every case below states its own reads.
  mocks.checkNotificationPermission.mockRejectedValue(
    new Error("the platform was never queried")
  );
  mocks.requestPermissions.mockRejectedValue(
    new Error("the prompt was never expected here")
  );
});

describe("syncNotificationPermission", () => {
  it("prompts on a fresh install and reports the answer", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(NEVER_ASKED);
    mocks.requestPermissions.mockResolvedValue(GRANTED);

    const state = await syncNotificationPermission(false);

    // `authorized` is reachable only through the prompt: the platform read says
    // `undetermined`. This is "it asked", stated as an outcome.
    expect(state.status).toBe("authorized");
    expect(state.asked).toBe(true);
    expect(state.gap).toBeNull();
  });

  it("does not prompt a second time once it has asked", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(NEVER_ASKED);
    mocks.requestPermissions.mockResolvedValue(GRANTED);

    const state = await syncNotificationPermission(true);

    expect(state.status).toBe("undetermined");
    expect(state.gap?.remedy).toBe("request");
  });

  it("surfaces a refusal instead of swallowing it", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(REFUSED);

    const state = await syncNotificationPermission(false);

    expect(state.gap?.severity).toBe("blocking");
    expect(state.gap?.remedy).toBe("notification-settings");
  });

  it("picks up a permission granted from Settings without a reinstall", async () => {
    // The foreground re-read, which is the whole answer to "a denial reversed
    // later is noticed". The app asked once, was refused, and the person went
    // to Settings; the next transition to `active` runs exactly this.
    mocks.checkNotificationPermission.mockResolvedValue(GRANTED);

    const state = await syncNotificationPermission(true);

    expect(state.status).toBe("authorized");
    expect(state.gap).toBeNull();
  });

  it("reports a reminder that will merely be late as its own, lesser gap", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(NO_EXACT_ALARM);

    const state = await syncNotificationPermission(true);

    expect(state.gap?.severity).toBe("degraded");
    expect(state.gap?.remedy).toBe("alarm-settings");
  });

  it("keeps `asked` true across a later refusal, so it cannot re-prompt", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(REFUSED);

    expect((await syncNotificationPermission(true)).asked).toBe(true);
  });

  it("says it is asking BEFORE the dialog goes up, not after it comes down", async () => {
    // The window that would otherwise re-open the dialog on top of itself. On
    // Android the prompt is its own activity, so dismissing it resumes the host
    // and RN emits AppState "active" — which runs this function again while
    // `requestPermissions()` is still awaiting. A caller told only by the
    // resolved result is still holding `asked === false` at that instant.
    //
    // Asserted by resolving the prompt from inside a second, concurrent call:
    // that call is the foreground event, and it must find the door already
    // shut.
    let concurrent: PermissionGateState | undefined;
    let hasAsked = false;
    let prompts = 0;
    mocks.checkNotificationPermission.mockResolvedValue(NEVER_ASKED);
    mocks.requestPermissions.mockImplementation(async () => {
      prompts += 1;
      if (prompts === 1) {
        concurrent = await syncNotificationPermission(hasAsked);
        // Dismissed, so the platform state has not moved.
        return NEVER_ASKED;
      }
      // Reachable ONLY by a second dialog, and answered differently so that a
      // second trip through the prompt is legible in the state it returns. Both
      // paths end at `undetermined` otherwise, and an assertion that cannot
      // tell them apart would pass either way.
      return GRANTED;
    });

    await syncNotificationPermission(false, () => {
      hasAsked = true;
    });

    expect(concurrent?.status).toBe("undetermined");
    expect(concurrent?.gap?.remedy).toBe("request");
    expect(prompts).toBe(1);
  });
});

describe("applyPermissionRemedy", () => {
  it("re-runs the prompt for a gap the prompt can still fix", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(NEVER_ASKED);
    mocks.requestPermissions.mockResolvedValue(GRANTED);

    const state = await applyPermissionRemedy("request");

    expect(state.status).toBe("authorized");
    expect(state.asked).toBe(true);
  });

  it("sends a refusal to the notification settings", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(REFUSED);
    mocks.openNotificationSettings.mockResolvedValue(undefined);

    await applyPermissionRemedy("notification-settings");

    expect(mocks.openNotificationSettings).toHaveBeenCalled();
    expect(mocks.openExactAlarmSettings).not.toHaveBeenCalled();
  });

  it("sends a missing exact alarm to the alarm settings", async () => {
    mocks.checkNotificationPermission.mockResolvedValue(NO_EXACT_ALARM);
    mocks.openExactAlarmSettings.mockResolvedValue(undefined);

    await applyPermissionRemedy("alarm-settings");

    expect(mocks.openExactAlarmSettings).toHaveBeenCalled();
    expect(mocks.openNotificationSettings).not.toHaveBeenCalled();
  });

  it("re-reads the platform after sending someone to Settings", async () => {
    // Not cosmetic. The person leaves for Settings and comes back; the state
    // they return to has to be the one the OS now holds, not the one that was
    // true when they tapped.
    mocks.checkNotificationPermission.mockResolvedValue(GRANTED);
    mocks.openNotificationSettings.mockResolvedValue(undefined);

    expect(
      (await applyPermissionRemedy("notification-settings")).gap
    ).toBeNull();
  });
});
