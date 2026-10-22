/**
 * F16 fix: "auth generation" — tăng mỗi lần bắt đầu một luồng login/register hoặc logout.
 * Continuation của request cũ (hoàn tất muộn) chỉ được phép saveSession/navigate nếu
 * generation không đổi trong lúc chờ — phiên lưu lại luôn là của luồng bắt đầu sau.
 */
let authGeneration = 0

export function bumpAuthGeneration(): number {
  return ++authGeneration
}

export function getAuthGeneration(): number {
  return authGeneration
}

/** Chỉ cho test: reset về 0 giữa các test. */
export function _resetAuthGenerationForTests(): void {
  authGeneration = 0
}
