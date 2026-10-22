/**
 * Vitest setup (mọi environment): jsdom không có window.matchMedia, mà antd
 * responsiveObserver (useBreakpoint) gọi nó khi render. Polyfill tối thiểu.
 */
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false
    }) as unknown as MediaQueryList
}
