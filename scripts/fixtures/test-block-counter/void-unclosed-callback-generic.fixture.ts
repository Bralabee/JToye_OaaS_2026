// EXPECT: VOID. The arrow must not hide the missing type-argument closer.
it.each<[() => void]([
  [() => {}],
  [() => {}],
])("unclosed callback type", () => {});