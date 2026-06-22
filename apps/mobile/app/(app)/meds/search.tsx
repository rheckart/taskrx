import type { RxNormSearchResult } from "@taskrx/shared";
import { useAction } from "convex/react";
import { useRouter } from "expo-router";
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

import { api } from "../../../../../packages/convex/convex/_generated/api";

const DEBOUNCE_MS = 300;

export default function MedSearchScreen(): ReactElement {
  const router = useRouter();
  const search = useAction(api.rxnorm.search);
  const [query, setQuery] = useState<string>("");
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

  const onPick = useCallback(
    (item: RxNormSearchResult): void => {
      router.navigate({
        pathname: "/meds/new",
        params: { rxcui: item.rxcui, name: item.name },
      } as never);
    },
    [router]
  );

  return (
    <View style={styles.container}>
      <TextInput
        autoFocus
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
