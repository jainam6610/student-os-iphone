'use client'

import { memo, useCallback } from 'react'
import { motion } from 'framer-motion'
import { type Habit } from '@/lib/habits/types'
import { HabitIcon } from './HabitIcon'
import { getTodayStr, formatHabitCueTime } from '@/lib/habits/utils'
import { HABIT_TIME_OF_DAY_LABELS } from '@/lib/habits/types'
import { cn } from '@/lib/utils'
import { staggerItem } from '@/components/motion'
import { Pencil, Archive, Trash2 } from 'lucide-react'

interface HabitCardProps {
  habit: Habit
  onToggle: (id: string) => void
  onEdit: (habit: Habit) => void
  onDelete: (id: string) => void
  onArchive: (id: string) => void
  ringingAlarmHabitId?: string | null
  onDismissAlarm?: () => void
  index: number
}

export const HabitCard = memo(function HabitCard({
  habit,
  onToggle,
  onEdit,
  onDelete,
  onArchive,
  ringingAlarmHabitId,
  onDismissAlarm,
  index,
}: HabitCardProps) {
  const today = getTodayStr()
  const isCompleted = habit.completedDates.includes(today)
  const isSkipped = habit.skippedDates?.includes(today)
  const isRinging = ringingAlarmHabitId === habit.id

  // Atomic Habits cue line: when + how long, so the card states the intention
  // instead of only the name. Falls back to nothing when a habit has no cue.
  const cueParts: string[] = []
  const timeOfDay = habit.timeOfDay ?? 'anytime'
  if (timeOfDay !== 'anytime') cueParts.push(HABIT_TIME_OF_DAY_LABELS[timeOfDay])
  if (habit.reminder?.enabled) {
    const clock = formatHabitCueTime(habit.reminder.times?.[0])
    if (clock) cueParts.push(clock)
  }
  if (habit.estimatedDuration) cueParts.push(`${habit.estimatedDuration} min`)
  const cue = cueParts.join(' · ')

  const handleToggle = useCallback(() => {
    onToggle(habit.id)
  }, [onToggle, habit.id])

  const handleEdit = useCallback((e: React.MouseEvent) => {
    e.stopPropagation()
    onEdit(habit)
  }, [onEdit, habit])

  const handleDelete = useCallback((e: React.MouseEvent) => {
    e.stopPropagation()
    onDelete(habit.id)
  }, [onDelete, habit.id])

  const handleArchive = useCallback((e: React.MouseEvent) => {
    e.stopPropagation()
    onArchive(habit.id)
  }, [onArchive, habit.id])

  const handleDismiss = useCallback((e: React.MouseEvent) => {
    e.stopPropagation()
    onDismissAlarm?.()
  }, [onDismissAlarm])

  return (
    <motion.div
      variants={staggerItem}
      initial="initial"
      animate="animate"
      layout
      className={cn(
        'glass-panel rounded-xl p-4 flex items-center justify-between group hover:border-primary/30 transition-all duration-300 cursor-pointer select-none',
        isCompleted && 'border-primary/30 bg-primary/5',
        isSkipped && 'opacity-60',
        isRinging && 'ring-2 ring-error/50 bg-error/5'
      )}
      onClick={handleToggle}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleToggle() } }}
      aria-label={`${habit.name}${isCompleted ? ' (completed)' : isSkipped ? ' (skipped)' : ''}`}
    >
      <div className="flex items-center gap-4 min-w-0">
        <div className={cn(
          'w-10 h-10 rounded-lg flex items-center justify-center border shrink-0',
          isCompleted
            ? 'bg-primary/10 text-primary border-primary/20'
            : 'bg-surface-variant/50 text-on-surface-variant border-primary/10',
          isRinging && 'border-error/30 bg-error/10 text-error'
        )}>
          <HabitIcon name={habit.icon} size={20} />
        </div>
        <div className="min-w-0">
          <h4 className={cn(
            'font-semibold text-on-surface text-sm truncate',
            isCompleted && 'line-through text-on-surface/50'
          )}>
            {habit.name}
          </h4>
          <p className="text-xs text-on-surface-variant truncate">
            {(habit.currentStreak ?? habit.streak ?? 0) > 0 ? `${habit.currentStreak ?? habit.streak ?? 0} day streak` : habit.category ? `${habit.category}` : 'No streak'}
          </p>
          {cue && (
            <p className="text-[10px] leading-tight text-on-surface-variant/60 truncate mt-0.5">
              {cue}
            </p>
          )}
        </div>
      </div>

      <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
        {(habit.currentStreak ?? habit.streak ?? 0) > 0 && (
          <motion.span
            key={habit.currentStreak ?? habit.streak ?? 0}
            initial={{ scale: 1.2 }}
            animate={{ scale: 1 }}
            className="text-xs font-bold text-primary mr-1 hidden sm:block"
          >
            {(habit.currentStreak ?? habit.streak ?? 0)}d
          </motion.span>
        )}

        {isRinging && (
          <motion.button
            initial={{ scale: 0.9 }}
            animate={{ scale: [1, 1.05, 1] }}
            transition={{ repeat: Infinity, duration: 1.5 }}
            onClick={handleDismiss}
            className="px-3 py-1.5 rounded-lg bg-error text-error-foreground text-xs font-semibold hover:bg-error/90 active:scale-[0.96] transition-all whitespace-nowrap"
            aria-label="Dismiss alarm"
          >
            Stop
          </motion.button>
        )}

        <button
          onClick={handleEdit}
          className="min-h-[44px] min-w-[44px] flex items-center justify-center hover:bg-surface-bright rounded-lg text-on-surface-variant hover:text-primary transition-colors"
          aria-label={`Edit ${habit.name}`}
        >
          <Pencil className="w-5 h-5" />
        </button>
        <button
          onClick={handleArchive}
          className="min-h-[44px] min-w-[44px] flex items-center justify-center hover:bg-surface-bright rounded-lg text-on-surface-variant hover:text-warning transition-colors"
          aria-label={`Archive ${habit.name}`}
        >
          <Archive className="w-5 h-5" />
        </button>
        <button
          onClick={handleDelete}
          className="min-h-[44px] min-w-[44px] flex items-center justify-center hover:bg-error/10 rounded-lg text-on-surface-variant hover:text-error transition-colors"
          aria-label={`Delete ${habit.name}`}
        >
          <Trash2 className="w-5 h-5" />
        </button>
      </div>
    </motion.div>
  )
})
