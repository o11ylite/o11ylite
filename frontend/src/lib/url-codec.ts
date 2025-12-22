import { decode, encode } from "@msgpack/msgpack"

/**
 * Serializes data using MessagePack and encodes it into a URL-safe Base64 string.
 */
export function urlSafeEncode(data: unknown): string {
  const packed: Uint8Array = encode(data)
  const binaryString = Array.from(packed, (byte) =>
    String.fromCharCode(byte)
  ).join("")

  return btoa(binaryString)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "")
}

/**
 * Decodes a URL-safe Base64 string back into its original data structure.
 */
export function urlSafeDecode<T>(str: string): T {
  let base64 = str.replace(/-/g, "+").replace(/_/g, "/")

  while (base64.length % 4) {
    base64 += "="
  }

  const binaryString = atob(base64)
  const uint8Array = Uint8Array.from(binaryString, (char) => char.charCodeAt(0))

  return decode(uint8Array) as T
}
