import type { RxNormSearchResult } from "@taskrx/shared";
import { useRouter } from "expo-router";
import { useCallback } from "react";
import type { ReactElement } from "react";

import { DrugSearch } from "../../../src/components/DrugSearch";

/**
 * Drug search, reached from the medication form.
 *
 * Everything this screen used to be is now `DrugSearch` — the query state, the
 * debounce, the stale-response guard and the result list — because the
 * natural-language entry surface needs the same search without leaving the
 * draft it is holding. What is left here is the only thing that was ever
 * specific to this route: where a pick goes.
 */
export default function MedSearchScreen(): ReactElement {
  const router = useRouter();

  const onPick = useCallback(
    (item: RxNormSearchResult): void => {
      router.navigate({
        pathname: "/meds/new",
        params: { rxcui: item.rxcui, name: item.name },
      });
    },
    [router]
  );

  return <DrugSearch onPick={onPick} autoFocus />;
}
