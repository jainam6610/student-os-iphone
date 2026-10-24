'use client'

import { motion } from 'framer-motion'
import { X, Shield, Eye, Bell, Clock, Smartphone, Activity, MonitorUp, Check, XCircle } from 'lucide-react'

export function PermissionsInfo({ onClose }: { onClose: () => void }) {
  const permissions = [
    {
      icon: Clock,
      name: 'Foreground Service',
      type: 'Required',
      purpose: 'Keep the Focus timer accurate in the background, play your alarm sound, and keep Deep Lock checking while a session runs.',
      note: 'Three foreground services are declared (focus_session_timer, alarm_playback, focus_shield_monitoring). Each runs only while its feature is active and shows a persistent notification.',
    },
    {
      icon: Eye,
      name: 'Accessibility Service',
      type: 'Required for Deep Lock',
      purpose: 'Detect which app is in the foreground, whether the YouTube Shorts / Instagram Reels player is open (on-screen element identifiers and their bounds), and — in a browser — the page address, so Deep Lock can block what you chose.',
      note: 'Does not read passwords, typed text, messages, notifications, or the content of pages you visit. Accessibility-derived information stays on this device and is never transmitted.',
    },
    {
      icon: Clock,
      name: 'Exact Alarms',
      type: 'Optional',
      purpose: 'Schedule study session and habit reminders at precise times.',
      note: 'Only used when you set study or habit alarms. Alarms are restored after a reboot (RECEIVE_BOOT_COMPLETED). Focus works without it.',
    },
    {
      icon: Smartphone,
      name: 'Battery Optimization Exemption',
      type: 'Recommended',
      purpose: 'Battery optimization exemption — keeps Focus timer and alarms running when the app is in the background.',
      note: 'Extends background reliability for Focus sessions and scheduled alarms.',
    },
    {
      icon: Bell,
      name: 'Notifications',
      type: 'Recommended',
      purpose: 'Show Focus Session completion alerts, Deep Lock warnings, and the alarms you scheduled.',
      note: 'Recommended for Deep Lock alerts. Focus works without it. Can be disabled at any time via system Settings.',
    },
    {
      icon: Activity,
      name: 'Usage Access',
      type: 'Required for Deep Lock',
      purpose: 'Identify the current foreground app so Deep Lock can block distractions.',
      note: 'Required only when Deep Lock is enabled. Not used by the Focus timer.',
    },
    {
      icon: Bell,
      name: 'Do Not Disturb access',
      type: 'Optional',
      purpose: 'Let Focus mode switch on Do Not Disturb while a session runs, if you choose that option.',
      note: 'Off by default. Revocable at any time in Settings → Notifications → Do Not Disturb access.',
    },
    {
      icon: MonitorUp,
      name: 'Full-screen alarm alerts',
      type: 'Optional',
      purpose: 'Full-screen intent (USE_FULL_SCREEN_INTENT) — lets a study or habit alarm you scheduled take over the screen so you never miss it.',
      note: 'Used only for alarms you scheduled (motivation reminders no longer use it). On Android 14+ you can grant or revoke this in Settings → Apps → Student OS → Notifications. Alarms still ring without it, but the full-screen alert may not appear.',
    },
  ]

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      className="fixed inset-0 z-modal-backdrop flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm overflow-y-auto"
      onClick={onClose}
    >
      <motion.div
        initial={{ scale: 0.95, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        exit={{ scale: 0.95, opacity: 0 }}
        transition={{ type: 'spring', stiffness: 400, damping: 30 }}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-2xl bg-card rounded-2xl border shadow-2xl overflow-hidden max-h-[90vh] flex flex-col"
      >
        <div className="flex items-center justify-between p-5 border-b border-border shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center">
              <Shield className="w-5 h-5 text-primary" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-foreground">Permissions</h2>
              <p className="text-xs text-muted-foreground">How Student OS uses permissions</p>
            </div>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-secondary/30 transition-colors" aria-label="Close">
            <X className="w-4 h-4 text-muted-foreground" />
          </button>
        </div>
        <div className="overflow-y-auto p-5 space-y-3">
          {permissions.map((perm, i) => (
            <div key={i} className="rounded-xl bg-surface-container-low border border-border/50 p-4">
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2">
                  <perm.icon className="w-4 h-4 text-primary" />
                  <h3 className="text-sm font-semibold text-foreground">{perm.name}</h3>
                </div>
                <span className={`text-[10px] font-medium px-2 py-0.5 rounded-full ${perm.type === 'Required' ? 'bg-primary/10 text-primary' : perm.type === 'Optional' ? 'bg-secondary text-muted-foreground' : 'bg-amber-500/10 text-amber-500'}`}>
                  {perm.type}
                </span>
              </div>
              <p className="text-xs text-muted-foreground leading-relaxed">{perm.purpose}</p>
              <p className="text-[10px] text-muted-foreground/60 mt-1 italic">{perm.note}</p>
            </div>
          ))}
          <div className="rounded-xl bg-green-500/5 border border-green-500/20 p-4">
            <div className="flex items-center gap-2 mb-2">
              <Check className="w-4 h-4 text-green-500" />
              <h3 className="text-sm font-semibold text-foreground">Permissions NOT Used</h3>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {['Location', 'Camera', 'Microphone', 'Contacts', 'SMS', 'Storage', 'Bluetooth', 'NFC', 'Body Sensors'].map((p) => (
                  <span key={p} className="text-[10px] px-2 py-1 rounded-md bg-surface-container-high text-muted-foreground/60">
                    <XCircle className="w-3 h-3 inline mr-1 text-muted-foreground/40" />
                    {p}
                  </span>
                ))}
              </div>
              <p className="text-[10px] text-muted-foreground/60 mt-2">
                Internet access is used by the embedded WebView/runtime and external links only. Accessibility-derived data is not transmitted. Imported ambient audio is stored inside the app&apos;s own local database (IndexedDB) and needs no storage permission.
              </p>
          </div>
        </div>
      </motion.div>
    </motion.div>
  )
}
