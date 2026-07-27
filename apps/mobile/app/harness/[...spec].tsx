import { useLocalSearchParams } from "expo-router";
import type { ReactElement } from "react";

import { HarnessScreen } from "../../src/components/HarnessScreen";

export default function HarnessSpecRoute(): ReactElement {
  const params = useLocalSearchParams<{ spec?: string | string[] }>();
  const raw = params.spec;
  const segments = raw === undefined ? [] : Array.isArray(raw) ? raw : [raw];
  return <HarnessScreen segments={segments} />;
}
