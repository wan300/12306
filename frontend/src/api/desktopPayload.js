// API bodies are JSON. Unwrap nested Vue proxies before Electron structured clone.
export function desktopPayload(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value))
}
