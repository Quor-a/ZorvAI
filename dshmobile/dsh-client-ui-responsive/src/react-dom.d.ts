// The browser platform supplies ReactDOM; only its synchronous dispatch API is borrowed here.
declare module 'react-dom' {
  export function flushSync<T>(callback: () => T): T
}
