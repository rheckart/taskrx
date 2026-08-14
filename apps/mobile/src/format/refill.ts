import type {
  Reminder,
  RefillProjection,
  UnprojectableReason,
} from "@taskrx/shared";
import { isBacklog } from "@taskrx/shared";

import { formatDay } from "./datetime";

/**
 * How the Refill surface says what a projection means.
 *
 * Here rather than inside `app/(app)/refill.tsx` for the reason
 * `src/format/percent.ts` is not inside `adherence.tsx`: every line below is a
 * judgement about what a number means, and on this surface the wrong sentence
 * over the right number is the whole failure mode. "Plenty left" over an empty
 * bottle and "0 days" over a supply nobody recorded are the two the acceptance
 * criteria name, and neither is visible to a test while it lives in a screen.
 *
 * The vocabulary is `CONTEXT.md`'s. A scheduled taking is an **Occurrence**, and
 * the glossary says outright to avoid "using 'dose' to mean a scheduled or
 * recorded taking" — so a supply is "enough for 12 more", never "12 more doses".
 * The adherence headline settled the same point when it became "14 of 18 taken".
 */

/** What is missing, said in the second person, for each reason there is. */
const NOT_TRACKED: Record<UnprojectableReason, string> = {
  "no-supply": "Add how many you have to see how long it lasts.",
  "no-dose-size": "Add how many you take at a time to see how long it lasts.",
  // Neither reaches the surface — it lists active medications — but both are
  // reasons a projection can be absent, and a record the compiler checks beats
  // a default arm that silently covers a case nobody meant.
  stopped: "This medication is stopped, so nothing is being used up.",
  "not-a-medication": "Only medications have a supply.",
};

/**
 * The headline: how long there is, in the fewest words that are true.
 *
 * The `undefined` day count has two causes and they must not share a sentence.
 * A supply that outlasts the walk really is plenty; a Reminder with no times set
 * has no run-out date because nothing is being taken, which is the opposite
 * fact — "Plenty left" above an empty bottle would be the misleading reading
 * the ticket's fourth criterion exists to prevent, arriving by another route.
 */
export function refillHeadline(
  medication: Reminder,
  projection: RefillProjection
): string {
  if (!projection.projected) return "Supply not tracked";

  const days = projection.daysRemaining;
  if (days === undefined) {
    return isBacklog(medication) ? "No times set" : "Plenty left";
  }
  if (days <= 0) return "Runs out today";
  if (days === 1) return "1 day left";
  return `${String(days)} days left`;
}

/** How much is left, counted in the takings it covers rather than in pills. */
function amountPhrase(occurrences: number): string {
  if (occurrences === 0) return "nothing left";
  if (occurrences === 1) return "enough for 1 more";
  return `enough for ${String(occurrences)} more`;
}

/**
 * The line under the headline, which is where the working is shown rather than
 * asserted. "12 days left" is a conclusion; "enough for 12 more, runs out Tue,
 * Aug 25" is the arithmetic, and someone who has just changed how often they
 * take something needs the arithmetic to trust the conclusion.
 */
export function refillDetail(
  medication: Reminder,
  projection: RefillProjection
): string {
  if (!projection.projected) return NOT_TRACKED[projection.reason];

  const amount = amountPhrase(projection.occurrencesRemaining);

  if (projection.runsOutAt !== undefined) {
    const day = formatDay(projection.runsOutAt);
    // An empty bottle has not "run out on" a future date — that date is when
    // the next one is wanted, which is a different sentence and the one that
    // says what to do about it.
    return projection.occurrencesRemaining === 0
      ? `${amount} — the next is due ${day}`
      : `${amount}, runs out ${day}`;
  }

  return isBacklog(medication)
    ? `${amount}, and no times set — so none are being used up`
    : `${amount}, further off than this can project`;
}

/**
 * The badge, or nothing.
 *
 * A standing "you're fine" on every row makes the two that are not fine harder
 * to find, which is the one job this surface has — so only a medication that
 * needs reordering gets a word, and only one that has already run out gets the
 * louder of the two.
 */
export type RefillUrgency = "none" | "reorder" | "out";

export function refillUrgency(projection: RefillProjection): RefillUrgency {
  if (!projection.projected || !projection.needsRefill) return "none";
  return projection.occurrencesRemaining === 0 ? "out" : "reorder";
}
