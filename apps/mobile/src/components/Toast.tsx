import { useEffect, useRef, useState } from "react";
import type { ReactElement } from "react";
import { Animated, Pressable, StyleSheet, Text, View } from "react-native";

export type ToastVariant = "success" | "error" | "info";

export interface ToastMessage {
  readonly id: string;
  readonly message: string;
  readonly variant: ToastVariant;
}

interface ToastProps {
  readonly toast: ToastMessage | null;
  readonly onDismiss: () => void;
}

const AUTO_DISMISS_MS = 3000;

function variantStyles(variant: ToastVariant) {
  switch (variant) {
    case "success":
      return {
        background: "#2e7d32",
        icon: "✓",
      };
    case "error":
      return {
        background: "#c62828",
        icon: "✕",
      };
    case "info":
      return {
        background: "#1565c0",
        icon: "ℹ",
      };
  }
}

export function Toast({ toast, onDismiss }: ToastProps): ReactElement | null {
  const opacity = useRef(new Animated.Value(0)).current;
  const translateY = useRef(new Animated.Value(-16)).current;
  const [visible, setVisible] = useState<boolean>(false);
  const dismissTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (toast === null) {
      if (visible) {
        Animated.parallel([
          Animated.timing(opacity, {
            toValue: 0,
            duration: 200,
            useNativeDriver: true,
          }),
          Animated.timing(translateY, {
            toValue: -16,
            duration: 200,
            useNativeDriver: true,
          }),
        ]).start(() => {
          setVisible(false);
        });
      }
      return;
    }

    setVisible(true);
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 250,
        useNativeDriver: true,
      }),
      Animated.timing(translateY, {
        toValue: 0,
        duration: 250,
        useNativeDriver: true,
      }),
    ]).start();

    if (dismissTimerRef.current !== null) {
      clearTimeout(dismissTimerRef.current);
    }
    dismissTimerRef.current = setTimeout(() => {
      onDismiss();
    }, AUTO_DISMISS_MS);

    return () => {
      if (dismissTimerRef.current !== null) {
        clearTimeout(dismissTimerRef.current);
      }
    };
  }, [toast, opacity, translateY, onDismiss, visible]);

  if (!visible || toast === null) {
    return null;
  }

  const stylesForVariant = variantStyles(toast.variant);

  const handlePress = (): void => {
    onDismiss();
  };

  return (
    <View style={styles.container} pointerEvents="box-none">
      <Animated.View
        style={[
          styles.toast,
          { backgroundColor: stylesForVariant.background },
          { opacity, transform: [{ translateY }] },
        ]}
      >
        <Pressable onPress={handlePress} style={styles.pressable}>
          <Text style={styles.icon}>{stylesForVariant.icon}</Text>
          <Text style={styles.message}>{toast.message}</Text>
        </Pressable>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    left: 0,
    paddingHorizontal: 16,
    pointerEvents: "box-none",
    position: "absolute",
    right: 0,
    top: 48,
    zIndex: 999,
  },
  icon: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "700",
    marginRight: 10,
  },
  message: {
    color: "#fff",
    flex: 1,
    fontSize: 15,
    fontWeight: "500",
    lineHeight: 20,
  },
  pressable: {
    alignItems: "center",
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  toast: {
    borderRadius: 10,
    elevation: 6,
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.15,
    shadowRadius: 8,
  },
});
