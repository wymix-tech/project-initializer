import { beforeEach } from 'vitest'
import { useToast } from '@/composables/useToast'

// 每个用例前清空全局 toast 队列，避免跨用例串扰
beforeEach(() => {
  useToast().clear()
})
