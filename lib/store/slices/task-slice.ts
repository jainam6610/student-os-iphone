import { sanitizeString } from '../helpers'
import { MAX_TASKS } from '../constants'
import { generateId } from '../../utils'
import { applyCompletionProgress, claimCreate, isDuplicateCreate, releaseCreate } from '../completion'
import type { Task } from '../types'

export interface TaskSlice {
  tasks: Task[]
  addTask: (task: Omit<Task, 'id' | 'createdAt'>) => void
  updateTask: (id: string, updates: Partial<Task>) => void
  deleteTask: (id: string) => void
  completeTask: (id: string) => void
}

export const createTaskSlice = (set: any, get: any, _api?: any): TaskSlice => ({
  tasks: [],
  addTask: (task) => set((state: any) => {
    if (state.tasks.length >= MAX_TASKS) return state
    const title = sanitizeString(task.title, 'Untitled', 500)
    // R5: a double-tap on "Add task" must not create two identical rows.
    if (!claimCreate(title) || isDuplicateCreate(state.tasks, title)) return state
    return { tasks: [...state.tasks, { ...task, id: generateId(), title, description: task.description ? sanitizeString(task.description, '', 2000) : undefined, priority: ['low', 'medium', 'high'].includes(task.priority) ? task.priority : 'medium', status: ['todo', 'in-progress', 'done'].includes(task.status) ? task.status : 'todo', createdAt: new Date() }] }
  }),
  updateTask: (id, updates) => set((state: any) => ({
    tasks: state.tasks.map((t: Task) => t.id === id ? { ...t, ...updates, title: updates.title ? sanitizeString(updates.title, t.title, 500) : t.title, priority: updates.priority ? (['low', 'medium', 'high'].includes(updates.priority) ? updates.priority : t.priority) : t.priority, status: updates.status ? (['todo', 'in-progress', 'done'].includes(updates.status) ? updates.status : t.status) : t.status } : t),
  })),
  deleteTask: (id) => {
    const task = get().tasks.find((t: Task) => t.id === id)
    // Deleting frees the title so it can be re-created immediately.
    if (task) releaseCreate(task.title)
    set((state: any) => ({ tasks: state.tasks.filter((t: Task) => t.id !== id) }))
  },
  completeTask: (id) => {
    set((state: any) => {
      const task = state.tasks.find((t: Task) => t.id === id)
      if (!task || task.status === 'done') return state
      return {
        tasks: state.tasks.map((t: Task) => t.id === id ? { ...t, status: 'done', completedAt: new Date() } : t),
        progress: applyCompletionProgress(state.progress, task.priority),
      }
    })
    try { get().checkAchievements() } catch {}
    try { get().recalculateProductivityScore() } catch {}
  },
})
