'use client'

import type { LucideIcon } from 'lucide-react'
import {
  AlarmClock,
  BedDouble,
  BookOpen,
  Brain,
  Briefcase,
  Brush,
  Coffee,
  Droplet,
  Dumbbell,
  Flower2,
  Footprints,
  GraduationCap,
  Heart,
  Leaf,
  Library,
  Moon,
  Music,
  Palette,
  PersonStanding,
  Phone,
  Star,
  Sun,
  Target,
  Trees,
  Umbrella,
  User,
  Users,
  Utensils,
  Zap,
} from 'lucide-react'
import { HABIT_ICON_EMOJI } from '@/lib/habits/types'

/**
 * Habit icons used to be emoji spans only. On WebViews without a colour emoji
 * font the glyphs fell back to tofu/raw text, and inside a 7-column, 44px grid
 * they overflowed a 360px screen. Lucide renders deterministically, and the
 * emoji path is kept (with an explicit font stack) for legacy stored values.
 */
const LUCIDE_ICONS: Record<string, LucideIcon> = {
  self_improvement: PersonStanding,
  water_drop: Droplet,
  auto_stories: BookOpen,
  fitness_center: Dumbbell,
  nightlight: Moon,
  restaurant: Utensils,
  emoji_nature: Leaf,
  bolt: Zap,
  track_changes: Target,
  alarm: AlarmClock,
  psychology: Brain,
  menu_book: Library,
  palette: Palette,
  music_note: Music,
  spa: Flower2,
  coffee: Coffee,
  cleaning_services: Brush,
  call: Phone,
  directions_run: Footprints,
  bedtime: BedDouble,
  forest: Trees,
  beach_access: Umbrella,
  sunny: Sun,
  star: Star,
  // Fix (b): category chips used to render a raw emoji text glyph (which falls
  // back to tofu/word text on WebViews without a colour emoji font). They now
  // use the same deterministic Lucide set as the habit icons.
  favorite: Heart,
  school: GraduationCap,
  person: User,
  work: Briefcase,
  group: Users,
}

const EMOJI_FONT_STACK =
  'system-ui, "Apple Color Emoji", "Segoe UI Emoji", "Noto Color Emoji", "Android Emoji", sans-serif'

interface Props {
  name?: string
  size?: number
  className?: string
}

export function HabitIcon({ name, size = 20, className }: Props) {
  const LucideGlyph = name ? LUCIDE_ICONS[name] : undefined
  if (LucideGlyph) {
    return <LucideGlyph size={size} strokeWidth={1.9} className={className} aria-hidden="true" />
  }
  // Legacy rows may store a literal emoji; anything unknown gets a star.
  const fallback = (name && HABIT_ICON_EMOJI[name]) || (name && /\p{Extended_Pictographic}/u.test(name) ? name : '⭐')
  return (
    <span
      className={className}
      aria-hidden="true"
      style={{ fontFamily: EMOJI_FONT_STACK, fontSize: size, lineHeight: 1 }}
    >
      {fallback}
    </span>
  )
}
