import { computed, ref } from 'vue'
import { adminApi, ApiError } from '@/api/client'

/**
 * 管理端会话（T6 表单化工作台）：token 仅存于会话内存，刷新后需重新输入。
 *
 * 各分区表单组件都需要同一 token 与同一份「登录态 / 忙碌态 / 提示条」，
 * 若各自持有会重复实现并互相不可见；这里收敛为单一会话源，由 `AdminPanel` 创建、
 * 通过 provide/inject 下发（也允许组件在测试中独立创建）。
 *
 * @param initialToken 初始 token（缺省空串，由登录框输入）
 */
export function useAdminSession(initialToken = '') {
  const token = ref(initialToken)
  const operator = ref('admin')
  const loggedIn = ref(false)
  const busy = ref(false)
  const message = ref('')
  const messageType = ref<'info' | 'error'>('info')

  /** 是否有未消费的提示条（模板据此决定是否渲染）。 */
  const hasMessage = computed(() => message.value !== '')

  /**
   * 展示提示条。
   *
   * @param type 提示类型
   * @param text 提示文本（发布失败时通常为 4002 明细全文）
   */
  function showText(type: 'info' | 'error', text: string): void {
    messageType.value = type
    message.value = text
  }

  /** 清空提示条。 */
  function clearText(): void {
    message.value = ''
  }

  /**
   * 登录校验：以一次读草稿请求验证 token 有效性。
   *
   * @returns 是否登录成功
   */
  async function login(): Promise<boolean> {
    busy.value = true
    try {
      await adminApi.getDraft('metadata', token.value)
      loggedIn.value = true
      showText('info', '登录成功')
      return true
    } catch (e) {
      showText('error', e instanceof ApiError ? e.detail : '登录失败')
      return false
    } finally {
      busy.value = false
    }
  }

  /**
   * 统一包裹一次异步操作：置忙碌、按需展示成功/失败提示、返回是否成功。
   *
   * @param fn       操作体
   * @param success  成功提示文案（缺省不提示）
   * @param fallback 失败兜底文案（后端未给出明细时使用）
   */
  async function run<T>(
    fn: () => Promise<T>,
    success?: string,
    fallback = '操作失败',
  ): Promise<T | null> {
    busy.value = true
    try {
      const result = await fn()
      if (success !== undefined) showText('info', success)
      return result
    } catch (e) {
      showText('error', e instanceof ApiError ? e.detail : fallback)
      return null
    } finally {
      busy.value = false
    }
  }

  return {
    token,
    operator,
    loggedIn,
    busy,
    message,
    messageType,
    hasMessage,
    showText,
    clearText,
    login,
    run,
  }
}

/** 管理端会话共享上下文类型。 */
export type AdminSession = ReturnType<typeof useAdminSession>

/** provide/inject 键：管理端会话。 */
export const ADMIN_SESSION_KEY = 'adminSession'
