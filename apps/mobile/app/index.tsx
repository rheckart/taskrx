import type { ReactElement } from "react";
import { StyleSheet, Text, View } from "react-native";

export default function IndexScreen(): ReactElement {
  return (
    <View style={styles.container}>
      <Text>TaskRx POC — Day 1 scaffold ready</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: "center",
    backgroundColor: "#fff",
    flex: 1,
    justifyContent: "center",
  },
});
