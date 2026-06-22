import { Stack } from "expo-router";
import type { ReactElement } from "react";

export default function RootLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen name="index" options={{ title: "TaskRx" }} />
    </Stack>
  );
}
