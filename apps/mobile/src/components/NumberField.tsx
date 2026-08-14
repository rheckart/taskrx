import { useState } from "react";
import type { ReactElement } from "react";
import { StyleSheet, Text, TextInput, View } from "react-native";

/**
 * A count the person can clear.
 *
 * An empty box means "no value", which is a different thing from zero — a
 * supply of 0 pills is a claim, and a blank field is the absence of one. Parsing
 * to `undefined` rather than to 0 is what keeps `remindersRepo.create` from
 * writing a refill threshold nobody asked for, and what lets the refill surface
 * tell "nothing recorded" from "none left".
 *
 * The box holds the text and the caller holds the number, which is not
 * redundancy: `pillsPerDose` is a `double`, and rendering `String(parsed)` back
 * into the box would erase the decimal point the moment it was typed — "1."
 * parses to 1, renders as "1", and the "5" of "1.5" has nowhere to land. So the
 * text is authoritative while the field has focus, and only what it parses to
 * reaches the caller. `initialValue`, not `value`: nothing outside re-drives
 * this box while it is being typed into.
 *
 * Lives here rather than in `add.tsx` because the refill surface edits the same
 * three numbers after a prescription is collected, and a second copy of this
 * would be a second answer to "what does an empty box mean".
 */
export function NumberField({
  label,
  initialValue,
  onChange,
}: {
  readonly label: string;
  readonly initialValue: number | undefined;
  readonly onChange: (next: number | undefined) => void;
}): ReactElement {
  const [text, setText] = useState<string>(
    initialValue === undefined ? "" : String(initialValue)
  );

  return (
    <View>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        style={styles.input}
        value={text}
        onChangeText={(next) => {
          setText(next);
          const parsed = Number.parseFloat(next);
          onChange(Number.isFinite(parsed) ? parsed : undefined);
        }}
        keyboardType="numeric"
        placeholder="—"
        placeholderTextColor="#999"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  input: {
    borderColor: "#ccc",
    borderRadius: 6,
    borderWidth: 1,
    fontSize: 16,
    marginTop: 4,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  label: {
    color: "#333",
    fontSize: 14,
    fontWeight: "600",
    marginTop: 16,
  },
});
