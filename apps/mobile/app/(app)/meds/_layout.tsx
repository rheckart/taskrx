import { Stack } from "expo-router";
import type { ReactElement } from "react";

export default function MedsLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen name="index" options={{ title: "My Medications" }} />
      <Stack.Screen name="search" options={{ title: "Search RxNorm" }} />
      <Stack.Screen name="new" options={{ title: "New Medication" }} />
    </Stack>
  );
}
