import { useUser } from "@clerk/clerk-expo";
import { useEffect, useRef, useState } from "react";
import type { ReactElement } from "react";
import { StyleSheet, Text, View } from "react-native";

import { authConfig } from "../config/clerk";
import { parseHarnessSpec, runHarnessAction } from "../notifications/harness";

/**
 * Renders and executes one harness action. Not linked from any UI; driven
 * only by adb deep links.
 *
 * Split in three because of one constraint: the auth-dependent actions need
 * the Clerk subject, `useUser()` throws when no `ClerkProvider` is above it,
 * and this route renders in BOTH trees — it is the only route the local-only
 * tree serves at all. So the hook is isolated in a component that mounts only
 * when `authConfig` says the provider is there, and the runner below it takes
 * the subject as a plain prop. Calling the hook unconditionally would take
 * down `/harness` in local-only mode, which is where harness cases 1-7 run.
 */
export function HarnessScreen({
  segments,
}: {
  readonly segments: readonly string[];
}): ReactElement {
  if (authConfig === null) {
    return <HarnessRunner segments={segments} userId={null} />;
  }
  return <AuthedHarnessScreen segments={segments} />;
}

/**
 * Waits for Clerk to finish loading before running anything.
 *
 * Running at `isLoaded === false` would report `signed_in=false` for a session
 * that is merely still restoring from the token cache — and the shell harness
 * reads that as "this case was never run", so a race would turn into a case
 * silently skipped rather than a visible error. Holding the runner unmounted
 * until the subject is settled means it runs exactly once, with the answer.
 */
function AuthedHarnessScreen({
  segments,
}: {
  readonly segments: readonly string[];
}): ReactElement {
  const { isLoaded, user } = useUser();

  if (!isLoaded) {
    return (
      <View style={styles.container}>
        <Text style={styles.text}>waiting for auth</Text>
      </View>
    );
  }
  return <HarnessRunner segments={segments} userId={user?.id ?? null} />;
}

function HarnessRunner({
  segments,
  userId,
}: {
  readonly segments: readonly string[];
  readonly userId: string | null;
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

    void runHarnessAction(spec, { userId })
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
  }, [key, segments, userId]);

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
