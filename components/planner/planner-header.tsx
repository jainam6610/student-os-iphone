'use client'

import { motion } from 'framer-motion'

import { staggerItem } from '@/components/motion'

interface PlannerHeaderProps {
  completionPercent: number
}

/**
 * Fix (a): `completionPercent` was passed in and then dropped on the floor, so
 * ticking a planner task never changed anything in the header — the screen
 * looked frozen even though the event had been written. It is rendered now.
 */
export function PlannerHeader({ completionPercent }: PlannerHeaderProps) {
  const percent = Math.max(0, Math.min(100, Math.round(completionPercent || 0)))
  return (
    <motion.section variants={staggerItem} className="flex items-end justify-between gap-4">
      <div className="space-y-0.5">
        <p className="text-[10px] uppercase tracking-[0.3em] text-primary opacity-60 font-semibold">Planner</p>
        <h1 className="text-[28px] md:text-[32px] font-medium tracking-tighter text-foreground leading-none">
          My Day
        </h1>
      </div>
      <div className="flex flex-col items-end gap-1" aria-label={`${percent}% of today's plan complete`}>
        <span className="text-[10px] uppercase tracking-[0.16em] text-muted-foreground font-semibold">
          Done today
        </span>
        <span className="text-lg font-semibold tabular-nums text-foreground">{percent}%</span>
        <span className="h-1 w-20 overflow-hidden rounded-full bg-secondary" aria-hidden="true">
          <span
            className="block h-full rounded-full bg-primary transition-[width] duration-300"
            style={{ width: `${percent}%` }}
          />
        </span>
      </div>
    </motion.section>
  )
}
