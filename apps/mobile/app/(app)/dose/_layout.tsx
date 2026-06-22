import { Stack } from "expo-router";
import type { ReactElement } from "react";

export default function DoseLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen name="log" options={{ title: "Dose Log" }} />
      <Stack.Screen name="history" options={{ title: "Dose History" }} />
    </Stack>
  );
}
