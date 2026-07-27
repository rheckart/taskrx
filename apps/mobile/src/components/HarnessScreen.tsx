import { useEffect, useRef, useState } from "react";
import type { ReactElement } from "react";
import { StyleSheet, Text, View } from "react-native";

import { parseHarnessSpec, runHarnessAction } from "../notifications/harness";

/**
 * Renders and executes one harness action. Not linked from any UI; driven
 * only by adb deep links.
 */
export function HarnessScreen({
  segments,
}: {
  readonly segments: readonly string[];
}): ReactElement {
  const lastKeyRef = useRef<string | null>(null);
  const [result, setResult] = useState<string>("idle");

  const key = segments.join("/");

  useEffect(() => {
    if (lastKeyRef.current === key) {
      return;
    }
    lastKeyRef.current = key;

    const spec = parseHarnessSpec(segments);
    console.log(`[harness] action=${spec.action} key=${key}`);

    void runHarnessAction(spec)
      .then((message) => {
        console.log(`[harness] result: ${message}`);
        setResult(message);
      })
      .catch((error: unknown) => {
        const description =
          error instanceof Error ? error.message : String(error);
        console.log(`[harness] error: ${description}`);
        setResult(`error: ${description}`);
      });
  }, [key, segments]);

  return (
    <View style={styles.container}>
      <Text style={styles.text}>{result}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 16,
  },
  text: {
    fontFamily: "monospace",
    fontSize: 14,
  },
});
