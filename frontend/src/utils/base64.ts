/**
 * 二进制 → base64 的**唯一**实现（原文在 `Composer.tsx` 内联，编辑态复用后提到此处）。
 *
 * <p>为什么用分块 `String.fromCharCode(...)`：`btoa` 只接受「每字符一个字节」的字符串，
 * 而 `apply`/展开运算符传参有引擎实参上限（常见 65535 上下）⇒ 5MB 图片一次性展开会
 * `RangeError: Maximum call stack size exceeded`。按 0x8000 分块是既有的安全口径。
 *
 * <p>⛔ 产出的是**纯 base64**（不带 `data:` 前缀）。后端 `Base64.decode` 与图像 block 都要求
 * 纯 base64 —— 需要 dataURL 的地方由调用方自己拼前缀。
 */
export function u8ToBase64(bytes: Uint8Array): string {
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}
