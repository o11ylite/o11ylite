export type FieldType = "time" | "str" | "num" | "enum"

export interface Field {
  name: string
  type: FieldType
}
