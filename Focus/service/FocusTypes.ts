export type SessionStatus = 'idle' | 'running' | 'paused' | 'completed' | 'cancelled'

export type SessionKind = 'focus' | 'break'

export interface FocusSession {
  sessionId: string
  kind: SessionKind
  status: SessionStatus
  startTime: number
  endTime: number
  plannedDuration: number
  elapsed: number
  remaining: number
  pausedDuration: number
  pauseCount: number
  /** Break time (minutes) measured from timestamps, not a tick counter. */
  breakMinutes: number
  /** Epoch-ms window of the break this session recorded (C2). 0 when none. */
  breakStartedAt?: number
  breakEndedAt?: number
  /** Epoch-ms window of the last pause (C2). 0 when none. */
  pauseStartedAt?: number
  pauseEndedAt?: number
  completed: boolean
  interrupted: boolean
}

export interface CompletionData {
  duration: number
  score: number
  interrupted: boolean
  /** Break minutes taken in/across this session. */
  breakMinutes?: number
}

export type FocusEventType =
  | 'sessionStarted'
  | 'sessionPaused'
  | 'sessionResumed'
  | 'sessionStopped'
  | 'sessionCompleted'
  | 'tick'

export interface FocusEvent {
  type: FocusEventType
  session: FocusSession
  remaining?: number
  elapsed?: number
}
