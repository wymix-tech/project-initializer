import { describe, expect, it, vi } from 'vitest'
import { useToast } from '@/composables/useToast'

describe('useToast', () => {
  it('push 后自动 3 秒消失', () => {
    vi.useFakeTimers()
    const { toasts, push, clear } = useToast()
    clear()
    const id = push('error', '生成失败：[1001] 元信息校验失败')
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0]).toMatchObject({ id, type: 'error', text: '生成失败：[1001] 元信息校验失败' })
    vi.advanceTimersByTime(3000)
    expect(toasts.value).toHaveLength(0)
    clear()
    vi.useRealTimers()
  })

  it('dismiss 立即移除指定 toast', () => {
    const { toasts, push, dismiss, clear } = useToast()
    clear()
    const id1 = push('info', 'a')
    push('info', 'b')
    dismiss(id1)
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0].text).toBe('b')
    clear()
  })

  it('dismiss 未知 id 不抛错', () => {
    const { dismiss, clear } = useToast()
    clear()
    expect(() => dismiss(99999)).not.toThrow()
    clear()
  })

  it('clear 清空全部 toast', () => {
    const { toasts, push, clear } = useToast()
    push('info', 'a')
    push('error', 'b')
    clear()
    expect(toasts.value).toHaveLength(0)
  })
})
