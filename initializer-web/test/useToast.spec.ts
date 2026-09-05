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

describe('useToast 递增 id 与定时器', () => {
  it('push 多条 toast id 严格递增', () => {
    const { toasts, push, clear } = useToast()
    clear()
    const id1 = push('info', 'a')
    const id2 = push('info', 'b')
    const id3 = push('error', 'c')
    expect(id1).toBeLessThan(id2)
    expect(id2).toBeLessThan(id3)
    expect(toasts.value.map((t) => t.id)).toEqual([id1, id2, id3])
    clear()
  })

  it('dismiss 已消失的 toast 不重复操作定时器', () => {
    vi.useFakeTimers()
    const { toasts, push, dismiss, clear } = useToast()
    clear()
    const id = push('info', 'x')
    // 先通过 dismiss 移除（会 clearTimeout 定时器）
    dismiss(id)
    expect(toasts.value).toHaveLength(0)
    // 再次 dismiss 同一 id 不抛错（定时器已删除）
    expect(() => dismiss(id)).not.toThrow()
    clear()
    vi.useRealTimers()
  })

  it('clear 清除所有定时器后不会触发自动消失', () => {
    vi.useFakeTimers()
    const { toasts, push, clear } = useToast()
    clear()
    push('info', 'a')
    push('info', 'b')
    clear()
    // clear 后推进定时器不应产生任何副作用（定时器已被 clearTimeout）
    vi.advanceTimersByTime(5000)
    expect(toasts.value).toHaveLength(0)
    vi.useRealTimers()
  })

  it('push 后 dismiss 立即移除，定时器不再触发', () => {
    vi.useFakeTimers()
    const { toasts, push, dismiss, clear } = useToast()
    clear()
    const id = push('info', 'temp')
    expect(toasts.value).toHaveLength(1)
    dismiss(id)
    expect(toasts.value).toHaveLength(0)
    // 推进 3 秒后不应重新出现（定时器已清除）
    vi.advanceTimersByTime(3000)
    expect(toasts.value).toHaveLength(0)
    clear()
    vi.useRealTimers()
  })

  it('clear 在空队列时安全调用', () => {
    const { toasts, clear } = useToast()
    clear()
    clear()
    expect(toasts.value).toHaveLength(0)
  })
})
