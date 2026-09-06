// EXPECT: jest/vitest -> 6 blocks. Function arrows are not generic closers.
it.each<[() => void]>([
  [() => {}],
  [() => {}],
])("callback %s", (callback) => callback());

test.each<[Record<string, <T>(value: T) => Promise<T>>, "<=>"]>([
  [{ run: async (value) => value }, "<=>"],
  [{ run: async (value) => value }, "<=>"],
])("nested callback %s", () => {});

const callbacks: Array<[() => Record<string, number>]> = [
  [() => ({ value: 1 })],
  [() => ({ value: 2 })],
];
it.each<[() => Record<string, number>]>(callbacks)("bound callback %s", () => {});