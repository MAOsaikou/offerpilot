import { createApp, h, nextTick, ref } from 'vue'
import { expect, it } from 'vitest'
import ResumeCertificateEditor from './ResumeCertificateEditor.vue'

it('证书字符串支持排序、重复值的定点编辑、删除和添加，禁用时保留内容', async () => {
  const certificates = ref(['证书A', '证书B', '证书A'])
  const disabled = ref(false)
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({
    render: () =>
      h(ResumeCertificateEditor, {
        modelValue: certificates.value,
        'onUpdate:modelValue': (value) => (certificates.value = value),
        disabled: disabled.value,
      }),
  })
  app.mount(root)
  try {
    const original = certificates.value
    root.querySelector<HTMLButtonElement>('[data-action="down"]')!.click()
    await nextTick()
    expect(certificates.value).toEqual(['证书B', '证书A', '证书A'])
    expect(original).toEqual(['证书A', '证书B', '证书A'])
    const input = root.querySelectorAll('input')[2]!
    input.value = '奖项C'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    expect(certificates.value).toEqual(['证书B', '证书A', '奖项C'])
    root
      .querySelectorAll('fieldset')[1]!
      .querySelector<HTMLButtonElement>(':scope > button')!
      .click()
    await nextTick()
    expect(certificates.value).toEqual(['证书B', '奖项C'])
    disabled.value = true
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-action="down"]')!.click()
    root.querySelector<HTMLButtonElement>('.section-heading button')!.click()
    expect(certificates.value).toEqual(['证书B', '奖项C'])
    disabled.value = false
    await nextTick()
    while (certificates.value.length) {
      root.querySelector<HTMLButtonElement>('fieldset > button')!.click()
      await nextTick()
    }
    expect(certificates.value).toEqual([])
    expect(root.textContent).toContain('尚未填写证书与奖项')
    root.querySelector<HTMLButtonElement>('.section-heading button')!.click()
    await nextTick()
    expect(certificates.value).toEqual([''])
    expect(root.querySelector<HTMLInputElement>('input')?.value).toBe('')
  } finally {
    app.unmount()
    root.remove()
  }
})
