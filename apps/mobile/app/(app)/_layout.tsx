import { Stack } from "expo-router";
import type { ReactElement } from "react";

export default function AppLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen name="index" options={{ title: "TaskRx" }} />
    </Stack>
  );
}
