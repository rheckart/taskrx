import { v } from "convex/values";

import { action } from "./_generated/server.js";

/**
 * RxNorm drug lookup actions.
 *
 * Convex Actions are used for external HTTP calls so the RxNorm fetch is
 * isolated from the deterministic query/mutation environment.
 *
 * Endpoints:
 * - Search: https://rxnav.nlm.nih.gov/REST/approximateTerm.json?term={query}
 * - Properties: https://rxnav.nlm.nih.gov/REST/rxcui/{rxcui}/properties.json
 */

const RXNORM_TIMEOUT_MS = 5000;
const MAX_SEARCH_RESULTS = 10;

interface RxNormCandidate {
  readonly rxcui?: string;
  readonly name?: string;
}

interface RxNormApproximateGroup {
  readonly candidate?: readonly RxNormCandidate[];
}

interface RxNormSearchApiResponse {
  readonly approximateGroup?: RxNormApproximateGroup;
}

interface RxNormPropertiesApiResponse {
  readonly properties?: {
    readonly rxcui?: string;
    readonly name?: string;
  };
}

function isRxNormSearchApiResponse(
  value: unknown
): value is RxNormSearchApiResponse {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const record = value as Record<string, unknown>;
  if (record.approximateGroup === undefined) {
    return true;
  }

  const group = record.approximateGroup;
  if (typeof group !== "object" || group === null) {
    return false;
  }

  const groupRecord = group as Record<string, unknown>;
  if (groupRecord.candidate === undefined) {
    return true;
  }

  return Array.isArray(groupRecord.candidate);
}

function isRxNormPropertiesApiResponse(
  value: unknown
): value is RxNormPropertiesApiResponse {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const record = value as Record<string, unknown>;
  if (record.properties === undefined) {
    return true;
  }

  const properties = record.properties;
  if (typeof properties !== "object" || properties === null) {
    return false;
  }

  const propertiesRecord = properties as Record<string, unknown>;
  const rxcuiValid =
    propertiesRecord.rxcui === undefined ||
    typeof propertiesRecord.rxcui === "string";
  const nameValid =
    propertiesRecord.name === undefined ||
    typeof propertiesRecord.name === "string";

  return rxcuiValid && nameValid;
}

async function fetchWithTimeout(
  url: string,
  timeoutMs: number
): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => {
    controller.abort();
  }, timeoutMs);

  try {
    const response = await fetch(url, { signal: controller.signal });
    return response;
  } finally {
    clearTimeout(timeoutId);
  }
}

export const search = action({
  args: { query: v.string() },
  returns: v.array(
    v.object({
      rxcui: v.string(),
      name: v.string(),
    })
  ),
  handler: async (_ctx, args) => {
    const encodedQuery = encodeURIComponent(args.query);
    const url = `https://rxnav.nlm.nih.gov/REST/approximateTerm.json?term=${encodedQuery}&maxEntries=${String(MAX_SEARCH_RESULTS)}`;

    try {
      const response = await fetchWithTimeout(url, RXNORM_TIMEOUT_MS);
      if (!response.ok) {
        return [];
      }

      const raw = await response.json();
      if (!isRxNormSearchApiResponse(raw)) {
        return [];
      }

      const candidates = raw.approximateGroup?.candidate ?? [];
      const results: { readonly rxcui: string; readonly name: string }[] = [];

      for (const candidate of candidates) {
        const rxcui = candidate.rxcui?.trim();
        const name = candidate.name?.trim();

        if (
          rxcui !== undefined &&
          rxcui.length > 0 &&
          name !== undefined &&
          name.length > 0
        ) {
          results.push({ rxcui, name });
        }
      }

      return results.slice(0, MAX_SEARCH_RESULTS);
    } catch (_error) {
      return [];
    }
  },
});

export const getByRxcui = action({
  args: { rxcui: v.string() },
  returns: v.union(
    v.object({
      rxcui: v.string(),
      name: v.string(),
      ingredients: v.optional(v.array(v.string())),
    }),
    v.null()
  ),
  handler: async (_ctx, args) => {
    const encodedRxcui = encodeURIComponent(args.rxcui);
    const url = `https://rxnav.nlm.nih.gov/REST/rxcui/${encodedRxcui}/properties.json`;

    try {
      const response = await fetchWithTimeout(url, RXNORM_TIMEOUT_MS);
      if (!response.ok) {
        return null;
      }

      const raw = await response.json();
      if (!isRxNormPropertiesApiResponse(raw)) {
        return null;
      }

      const properties = raw.properties;
      if (properties === undefined) {
        return null;
      }

      const name = properties.name?.trim();
      if (name === undefined || name.length === 0) {
        return null;
      }

      return {
        rxcui: args.rxcui,
        name,
      };
    } catch (_error) {
      return null;
    }
  },
});
