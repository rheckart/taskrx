import type { ReactElement } from "react";
import { StyleSheet, View } from "react-native";

interface SkeletonProps {
  readonly width?: number | `${number}%`;
  readonly height?: number;
  readonly borderRadius?: number;
  readonly style?: Record<string, unknown>;
}

export function Skeleton({
  width = "100%",
  height = 16,
  borderRadius = 6,
  style,
}: SkeletonProps): ReactElement {
  return (
    <View
      style={[styles.skeleton, { width, height, borderRadius }, style ?? {}]}
    />
  );
}

interface SkeletonRowProps {
  readonly lines?: number;
}

export function SkeletonRow({ lines = 3 }: SkeletonRowProps): ReactElement {
  return (
    <View style={styles.row}>
      {Array.from({ length: lines }).map((_, index) => (
        <Skeleton
          key={index.toString()}
          width={index === 0 ? "60%" : "90%"}
          height={index === 0 ? 18 : 14}
          style={index === 0 ? {} : { marginTop: 8 }}
        />
      ))}
    </View>
  );
}

interface SkeletonListProps {
  readonly rows?: number;
  readonly linesPerRow?: number;
}

export function SkeletonList({
  rows = 6,
  linesPerRow = 3,
}: SkeletonListProps): ReactElement {
  return (
    <View style={styles.list}>
      {Array.from({ length: rows }).map((_, index) => (
        <SkeletonRow key={index.toString()} lines={linesPerRow} />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  list: {
    paddingVertical: 8,
  },
  row: {
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  skeleton: {
    backgroundColor: "#e0e0e0",
  },
});
