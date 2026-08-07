import type { RxNormSearchResult } from "@taskrx/shared";
import { useAction } from "convex/react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import { api } from "../../../../packages/convex/convex/_generated/api";

/**
 * RxNorm drug search, as a component rather than a screen.
 *
 * It was a screen — `meds/search.tsx` — and that is exactly one entry point:
 * search, then land on the medication form. Natural-language entry needs the
 * same search from a surface that is already holding a draft, and a screen
 * cannot be used that way without leaving and coming back. A round trip through
 * the router would either lose the draft on the way out or need it serialized
 * into route params and rebuilt on the way in, and a draft rebuilt from a URL
 * is a second definition of what a draft is.
 *
 * So the search knows nothing about where a pick goes: it reports the RxNorm
 * row and the caller decides. `meds/search.tsx` routes to the form with it; the
 * entry screen applies it to the draft in place, without unmounting.
 *
 * The debounce and the sequence guard are the reason this is worth sharing
 * rather than retyping. `requestSeqRef` is what stops a slow response for
 * "met" from overwriting the results for "metformin" — a stale-response race
 * that is invisible until someone types faster than the network.
 */

const DEBOUNCE_MS = 300;

export interface DrugSearchProps {
  readonly onPick: (result: RxNormSearchResult) => void;
  /** Seeds the query, so a draft's name is already searched for on arrival. */
  readonly initialQuery?: string;
  readonly autoFocus?: boolean;
}

export function DrugSearch({
  onPick,
  initialQuery = "",
  autoFocus = false,
}: DrugSearchProps): ReactElement {
  const search = useAction(api.rxnorm.search);
  const [query, setQuery] = useState<string>(initialQuery);
  const [results, setResults] = useState<readonly RxNormSearchResult[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [hasSearched, setHasSearched] = useState<boolean>(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requestSeqRef = useRef<number>(0);

  const runSearch = useCallback(
    async (term: string): Promise<void> => {
      const trimmed = term.trim();
      if (trimmed.length === 0) {
        setResults([]);
        setError(null);
        setHasSearched(false);
        setLoading(false);
        return;
      }

      const seq = ++requestSeqRef.current;
      setLoading(true);
      setError(null);
      try {
        const rows = await search({ query: trimmed });
        if (seq !== requestSeqRef.current) {
          return;
        }
        setResults(rows);
        setHasSearched(true);
      } catch (err: unknown) {
        if (seq !== requestSeqRef.current) {
          return;
        }
        const message = err instanceof Error ? err.message : "Search failed";
        setError(message);
        setResults([]);
        setHasSearched(true);
      } finally {
        if (seq === requestSeqRef.current) {
          setLoading(false);
        }
      }
    },
    [search]
  );

  useEffect(() => {
    if (debounceRef.current !== null) {
      clearTimeout(debounceRef.current);
    }
    debounceRef.current = setTimeout(() => {
      void runSearch(query);
    }, DEBOUNCE_MS);
    return () => {
      if (debounceRef.current !== null) {
        clearTimeout(debounceRef.current);
      }
    };
  }, [query, runSearch]);

  return (
    <View style={styles.container}>
      <TextInput
        autoFocus={autoFocus}
        placeholder="Search RxNorm (e.g. metformin)"
        placeholderTextColor="#999"
        style={styles.input}
        value={query}
        onChangeText={setQuery}
        autoCorrect={false}
        autoCapitalize="none"
        returnKeyType="search"
      />

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator />
        </View>
      ) : error !== null ? (
        <View style={styles.center}>
          <Text style={styles.errorText}>Search failed: {error}</Text>
        </View>
      ) : !hasSearched ? (
        <View style={styles.center}>
          <Text style={styles.hint}>Type a drug name to search RxNorm.</Text>
        </View>
      ) : results.length === 0 ? (
        <View style={styles.center}>
          <Text style={styles.hint}>
            No matches for &ldquo;{query.trim()}&rdquo;.
          </Text>
        </View>
      ) : (
        <FlatList
          data={results}
          keyExtractor={(item) => item.rxcui}
          renderItem={({ item }) => (
            <Pressable
              onPress={() => {
                onPick(item);
              }}
              style={({ pressed }) => [
                styles.row,
                pressed ? styles.rowPressed : null,
              ]}
            >
              <Text style={styles.rowName}>{item.name}</Text>
              <Text style={styles.rowRxcui}>RxCUI {item.rxcui}</Text>
            </Pressable>
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  center: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    padding: 24,
  },
  container: {
    backgroundColor: "#fff",
    flex: 1,
  },
  errorText: {
    color: "#c00",
    textAlign: "center",
  },
  hint: {
    color: "#666",
    textAlign: "center",
  },
  input: {
    borderBottomColor: "#ccc",
    borderBottomWidth: 1,
    borderRadius: 0,
    fontSize: 16,
    margin: 16,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  row: {
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  rowName: {
    fontSize: 16,
    fontWeight: "500",
  },
  rowPressed: {
    backgroundColor: "#f5f5f5",
  },
  rowRxcui: {
    color: "#666",
    fontSize: 13,
    marginTop: 2,
  },
});
