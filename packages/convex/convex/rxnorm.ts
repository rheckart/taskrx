import { v } from "convex/values";

import { action } from "./_generated/server.js";

/**
 * RxNorm drug search action.
 *
 * Convex Actions are used for external HTTP calls so the RxNorm fetch is
 * isolated from the deterministic query/mutation environment.
 *
 * Endpoint: https://rxnav.nlm.nih.gov/REST/approximateTerm.json?term={query}
 *
 * This is a stub for Day 2. Day 3 will implement the fetch + response mapping.
 */

export const search = action({
  args: { query: v.string() },
  returns: v.array(
    v.object({
      rxcui: v.string(),
      name: v.string(),
    })
  ),
  handler: (_ctx, _args) => {
    // TODO(Day 3): fetch from RxNorm with timeout + error handling, map to
    // { rxcui, name }[], and return the top 10 matches.
    return [];
  },
});
