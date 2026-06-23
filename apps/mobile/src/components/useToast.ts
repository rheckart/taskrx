import { useCallback, useEffect, useState } from "react";
import type { ReactElement } from "react";

import { Toast, type ToastMessage, type ToastVariant } from "./Toast";

let currentToast: ToastMessage | null = null;
const listeners = new Set<(toast: ToastMessage | null) => void>();

function notifyListeners(): void {
  for (const listener of listeners) {
    listener(currentToast);
  }
}

function showToast(message: string, variant: ToastVariant): void {
  currentToast = {
    id: `${Date.now().toString()}-${Math.random().toString(36).slice(2, 8)}`,
    message,
    variant,
  };
  notifyListeners();
}

function dismissToast(): void {
  currentToast = null;
  notifyListeners();
}

export interface UseToastResult {
  readonly showToast: {
    (message: string, variant?: ToastVariant): void;
    readonly success: (message: string) => void;
    readonly error: (message: string) => void;
    readonly info: (message: string) => void;
  };
  readonly ToastContainer: () => ReactElement | null;
}

export function useToast(): UseToastResult {
  const [toast, setToast] = useState<ToastMessage | null>(currentToast);

  useEffect(() => {
    listeners.add(setToast);
    return () => {
      listeners.delete(setToast);
    };
  }, []);

  const show = useCallback(
    (message: string, variant: ToastVariant = "info") => {
      showToast(message, variant);
    },
    []
  );

  const showSuccess = useCallback((message: string): void => {
    showToast(message, "success");
  }, []);

  const showError = useCallback((message: string): void => {
    showToast(message, "error");
  }, []);

  const showInfo = useCallback((message: string): void => {
    showToast(message, "info");
  }, []);

  const handleDismiss = useCallback((): void => {
    dismissToast();
  }, []);

  const ToastContainer = useCallback((): ReactElement | null => {
    return Toast({ toast, onDismiss: handleDismiss });
  }, [toast, handleDismiss]);

  return {
    showToast: Object.assign(show, {
      success: showSuccess,
      error: showError,
      info: showInfo,
    }),
    ToastContainer,
  };
}
