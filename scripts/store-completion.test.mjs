import assert from 'node:assert/strict'
import test from 'node:test'
import { useStore } from '../lib/store/index.ts'
import { getXPDataForRange } from '../lib/analytics-engine.ts'

function resetState() {
  const initial = useStore.getInitialState()
  useStore.setState({
    ...initial,
    tasks: [],
    habits: [],
    events: [],
    focusSessions: [],
    achievements: initial.achievements.map((a) => ({ ...a, unlocked: false, progress: 0 })),
    progress: { ...initial.progress, xp: 0, level: 1, totalTasksCompleted: 0 },
  })
}

let seq = 0
const unique = (label) => label + ' ' + (++seq)

const baseTask = () => ({
  title: unique('Task under test'),
  subject: 'Physics',
  priority: 'high',
  status: 'todo',
})

const baseEvent = () => ({
  title: unique('Planner under test'),
  startTime: new Date(),
  endTime: new Date(Date.now() + 3600000),
  type: 'study',
  priority: 'medium',
})

test('task completion and planner completion share one XP/progress path (R4)', async () => {
  resetState()
  useStore.getState().addTask(baseTask())
  const task = useStore.getState().tasks[0]
  useStore.getState().completeTask(task.id)
  const afterTask = useStore.getState().progress
  assert.equal(afterTask.xp, 50, 'high-priority task pays 50 XP')
  assert.equal(afterTask.totalTasksCompleted, 1)

  const before = afterTask.xp
  useStore.getState().addEvent(baseEvent())
  const event = useStore.getState().events[0]
  await useStore.getState().markEventComplete(event.id, true)
  const afterEvent = useStore.getState().progress
  assert.equal(afterEvent.xp, before + 30, 'planner event pays the same 30 XP as a medium task')
  assert.equal(afterEvent.totalTasksCompleted, 2, 'planner completion counts as completed work')
})

test('planner completion cannot farm XP by toggling (a)', async () => {
  resetState()
  useStore.getState().addEvent(baseEvent())
  const event = useStore.getState().events[0]
  await useStore.getState().markEventComplete(event.id, true)
  const afterFirst = useStore.getState().progress.xp
  await useStore.getState().markEventComplete(event.id, true)
  assert.equal(useStore.getState().progress.xp, afterFirst, 're-completing an already completed event is a no-op')
  await useStore.getState().markEventComplete(event.id, false)
  await useStore.getState().markEventComplete(event.id, true)
  assert.equal(useStore.getState().progress.xp, afterFirst, 'reopening does not reset the payout marker')
  const reopened = useStore.getState().events[0]
  assert.equal(reopened.completed, true)
  assert.deepEqual(
    useStore.getState().events[0].history?.map((h) => h.action).slice(-3),
    ['completed', 'reopened', 'completed'],
    'history records the real transition, not a second "completed" for a reopen',
  )
})

test('create actions are idempotent on double-tap (R5)', async () => {
  resetState()
  useStore.getState().addTask({ ...baseTask(), title: 'Double tap task' })
  useStore.getState().addTask({ ...baseTask(), title: 'Double tap task' })
  assert.equal(useStore.getState().tasks.length, 1, 'task created once')

  useStore.getState().addHabit({
    name: 'Double tap habit',
    icon: 'star',
    color: '#7dd3fc',
    frequency: 'daily',
  })
  useStore.getState().addHabit({
    name: 'Double tap habit',
    icon: 'star',
    color: '#7dd3fc',
    frequency: 'daily',
  })
  assert.equal(useStore.getState().habits.length, 1, 'habit created once')

  await useStore.getState().addEvent({ ...baseEvent(), title: 'Double tap event' })
  await useStore.getState().addEvent({ ...baseEvent(), title: 'Double tap event' })
  assert.equal(useStore.getState().events.length, 1, 'event created once')
})

test('a first create after the guard window is not swallowed', async () => {
  resetState()
  const stale = {
    ...baseEvent(),
    title: 'Legacy event',
  }
  useStore.setState({
    events: [{
      id: 'legacy-1',
      ...stale,
      createdAt: new Date(Date.now() - 60_000),
      history: [],
      focusSessionIds: [],
    }],
  })
  await useStore.getState().addEvent({ ...baseEvent(), title: 'Legacy event' })
  assert.equal(useStore.getState().events.length, 2, 'an older same-name event must not block a new one')
})

test('stopped-early sessions count minutes but never unlock achievements (c)', () => {
  resetState()
  useStore.setState({
    focusSessions: [{
      id: 'partial-1',
      startTime: new Date(Date.now() - 10 * 60 * 1000),
      endTime: new Date(),
      duration: 10,
      targetDuration: 60,
      type: 'deep-work',
      completed: false,
      interrupted: true,
      status: 'interrupted',
      createdAt: new Date(),
    }],
  })
  const xp = getXPDataForRange('7d', useStore.getState().focusSessions, [], new Date())
  assert.equal(xp.totalXp, 20, 'partial minutes still count toward time-derived XP')

  useStore.getState().checkAchievements()
  const firstFocus = useStore.getState().achievements.find((a) => a.id === 'first-focus')
  assert.equal(firstFocus?.unlocked, false, 'a stopped-early session must not unlock First Focus')

  useStore.setState({
    focusSessions: [{
      id: 'done-1',
      startTime: new Date(Date.now() - 30 * 60 * 1000),
      endTime: new Date(),
      duration: 30,
      targetDuration: 30,
      type: 'deep-work',
      completed: true,
      interrupted: false,
      status: 'completed',
      createdAt: new Date(),
    }],
  })
  useStore.getState().checkAchievements()
  assert.equal(useStore.getState().achievements.find((a) => a.id === 'first-focus')?.unlocked, true, 'a completed session unlocks it')
})

test('planner completion shows up in the analytics XP chart (R4)', async () => {
  resetState()
  useStore.getState().addEvent(baseEvent())
  const event = useStore.getState().events[0]
  await useStore.getState().markEventComplete(event.id, true)
  const xp = getXPDataForRange('7d', useStore.getState().focusSessions, useStore.getState().tasks, new Date(), useStore.getState().events)
  assert.equal(xp.totalXp, 30, 'completed planner event contributes its 30 XP to the chart')
})

test('habit tick moves streak, analytics and dashboard counters immediately (e)', async () => {
  resetState()
  const today = new Date().toISOString().slice(0, 10)
  useStore.getState().addHabit({ name: unique('Probe habit'), icon: 'star', color: '#7dd3fc', frequency: 'daily' })
  const habit = useStore.getState().habits[0]
  await useStore.getState().completeHabit(habit.id, today)
  const after = useStore.getState().habits[0]
  assert.equal(after.currentStreak, 1, 'streak recomputed on the same tick')
  assert.equal(after.streak, 1)
  assert.equal(after.completedDates.includes(today), true, 'heatmap source updated')
  assert.equal(useStore.getState().progress.xp, 15, 'check-in pays out immediately')

  // Dashboard-style counters derive straight from `habits`, so they move too.
  const dashboardDone = useStore.getState().habits.filter((h) => h.completedDates.includes(today) && !h.archived).length
  assert.equal(dashboardDone, 1, 'dashboard habit counter reflects the tick without navigation')

  const { computeHabitAnalytics } = await import('../lib/habits/analytics.ts')
  const analytics = computeHabitAnalytics(useStore.getState().habits)
  assert.equal(analytics.overall.currentStreak >= 1, true, 'analytics recomputed from the same array')
})
