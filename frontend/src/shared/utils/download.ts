export function downloadText(
  name: string,
  content: string,
  type = 'text/plain;charset=utf-8',
): void {
  downloadBlob(name, new Blob([content], { type }))
}

export function downloadBlob(name: string, blob: Blob): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = [...name]
    .map((character) =>
      character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127 ? '_' : character,
    )
    .join('')
    .replace(/[<>:"/\\|?*]/g, '_')
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
