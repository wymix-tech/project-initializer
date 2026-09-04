import { readonly, ref } from 'vue'

/** toast 消息类型。 */
export type ToastType = 'info' | 'error'

export interface ToastMessage {
  id: number
  type: ToastType
  text: string
}

let seq = 0
const toasts = ref<ToastMessage[]>([])
const timers = new Map<number, ReturnType<typeof setTimeout>>()

/** 全局 toast 队列：组件通过 currentToasts 只读消费。 */
export function useToast() {
  /**
   * 推送一条 toast，3 秒后自动消失。
   *
   * @param type 消息类型
   * @param text 消息文本
   * @returns 消息 id
   */
  function push(type: ToastType, text: string): number {
    const id = ++seq
    toasts.value = [...toasts.value, { id, type, text }]
    timers.set(
      id,
      setTimeout(() => dismiss(id), 3000),
    )
    return id
  }

  /**
   * 立即移除指定 toast。
   *
   * @param id 消息 id
   */
  function dismiss(id: number): void {
    const timer = timers.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.delete(id)
    }
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  /** 清空全部 toast（测试用）。 */
  function clear(): void {
    timers.forEach((t) => clearTimeout(t))
    timers.clear()
    toasts.value = []
  }

  return { toasts: readonly(toasts), push, dismiss, clear }
}
