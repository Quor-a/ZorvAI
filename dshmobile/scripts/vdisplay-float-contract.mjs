/** Check the state returned by a Boolean setter through its authoritative getter. */
export function booleanReadbackMatches(expected, actual) {
  return typeof expected === 'boolean' && typeof actual === 'boolean' && actual === expected
}
