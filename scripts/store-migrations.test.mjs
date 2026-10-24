import assert from 'node:assert/strict'
import test from 'node:test'
import { useStore } from '../lib/store/index.ts'
import { migrateStoreState } from '../lib/store/migrations.ts'

const initialState = useStore.getInitialState()
const persistedFields = Object.keys(useStore.persist.getOptions().partialize(initialState))

const fixtures = [
  {
    version: 5,
    state: {
      userName: 'v5 student', tasks: [{ id: 'task-5', title: 'Legacy task' }],
      progress: { xp: 25 }, habits: [{ id: 'habit-5', completedDates: ['2026-10-20'] }],
      focusSessions: [{ id: 'session-5', startTime: new Date('2026-10-20T10:00:00Z'), duration: 12, targetDuration: 30, completed: true }],
      events: [{ id: 'event-5', title: 'Legacy event', startTime: new Date('2026-10-21T10:00:00Z'), endTime: new Date('2026-10-21T11:00:00Z') }],
    },
  },
  {
    version: 10,
    state: {
      userName: 'v10 student', tasks: [{ id: 'task-10', title: 'Task', status: 'todo' }],
      habits: [{ id: 'habit-10', completedDates: [] }], focusSessions: [], events: [],
      exams: [{ id: 'exam-10', title: 'Exam', date: new Date('2026-11-01') }], assignments: [],
    },
  },
  {
    version: 17,
    state: {
      userName: 'v17 student', tasks: [], habits: [], focusSessions: [], events: [],
      syllabus: { subjects: [{ id: 'math', topics: [{ id: 'limits', title: 'Limits' }] }] },
      exams: [{ id: 'exam-17', title: 'Exam', date: new Date('2026-11-02') }], assignments: [],
    },
  },
  {
    version: 27,
    state: {
      userName: 'store student', tasks: [], habits: [], focusSessions: [], events: [],
      exams: [], assignments: [], sessionBlockedApps: ['com.example.focus'],
    },
  },
]

function assertTypeMatchesDefault(field, actual, expected) {
  if (Array.isArray(expected)) {
    assert.ok(Array.isArray(actual), `${field} must remain an array`)
  } else if (expected instanceof Date) {
    assert.ok(actual instanceof Date, `${field} must remain a Date`)
  } else if (expected === null) {
    assert.ok(actual === null || (typeof actual === 'object' && !Array.isArray(actual)), `${field} must be nullable object data`)
  } else if (expected && typeof expected === 'object') {
    assert.ok(actual && typeof actual === 'object' && !Array.isArray(actual), `${field} must remain an object`)
  } else {
    assert.equal(typeof actual, typeof expected, `${field} must be ${typeof expected}`)
  }
}

for (const fixture of fixtures) {
  test(`schema v${fixture.version} migrates through the current schema`, () => {
    const migrated = migrateStoreState(structuredClone(fixture.state), fixture.version)
    const finalState = { ...initialState, ...migrated }
    for (const field of persistedFields) {
      assert.ok(Object.hasOwn(finalState, field), `missing current persisted field ${field}`)
      assertTypeMatchesDefault(field, finalState[field], initialState[field])
    }
    assert.ok(Array.isArray(finalState.tasks))
    assert.ok(Array.isArray(finalState.habits))
    assert.ok(Array.isArray(finalState.focusSessions))
    assert.ok(Array.isArray(finalState.events))
    assert.ok(Array.isArray(finalState.exams))
    assert.ok(Array.isArray(finalState.assignments))
    assert.ok(Array.isArray(finalState.sessionBlockedApps))
  })
}

test('v5 focus sessions receive the status migration', () => {
  const migrated = migrateStoreState(structuredClone(fixtures[0].state), 5)
  assert.equal(migrated.focusSessions[0].status, 'completed')
})

test('v17 syllabus subjects migrate topics into chapters', () => {
  const migrated = migrateStoreState(structuredClone(fixtures[2].state), 17)
  assert.equal(migrated.syllabus.subjects[0].chapters[0].topics[0].id, 'limits')
})

test('v28 turns the screen-reading content filters off', () => {
  // Shorts/Reels/adult/study-mode inspect the accessibility view tree (text,
  // content descriptions, view IDs, bounds). v28 opts every install out.
  const migrated = migrateStoreState(
    { blockShorts: true, blockReels: true, blockAdultWebsites: true, youtubeStudyMode: true },
    27,
  )
  assert.equal(migrated.blockShorts, false)
  assert.equal(migrated.blockReels, false)
  assert.equal(migrated.blockAdultWebsites, false)
  assert.equal(migrated.youtubeStudyMode, false)

  // Whole-app lists are untouched by that migration.
  const withBlocks = migrateStoreState({ blockShorts: true, alwaysBlockedApps: ['com.google.android.youtube'] }, 27)
  assert.deepEqual(withBlocks.alwaysBlockedApps, ['com.google.android.youtube'])
})
