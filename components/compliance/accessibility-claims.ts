// ponytail: single source of truth for AccessibilityService data-use claims.
// The prominent disclosure modal, the in-app Privacy Policy, docs/, and the
// Play Console declaration all quote these exact strings — edit here only.
//
// The two data-type LABELS ("Other personal info", "Other user-generated
// content") are inlined verbatim at each render site so a source grep of
// AccessibilityDisclosure.tsx / PrivacyPolicy.tsx finds them literally.
// They are Google policy taxonomy terms — never reword either label.

export const ACCESSIBILITY_CLAIMS = {
  purpose:
    'Student OS uses the Android Accessibility Service to detect the foreground app during user-initiated focus sessions, in order to block distracting apps as part of the Deep Lock feature. Inside YouTube and Instagram it also checks whether the short-form video player (Shorts / Reels) is on screen, using on-screen element identifiers and their bounds, so a focus session can end short-form browsing early. Inside a supported browser it reads the address of the page you are on, only to compare it with the website list you configured.',

  coreFunctionality:
    'Deep Lock is an opt-in study focus tool that blocks user-selected distracting apps during a session the student starts voluntarily. The Accessibility Service is not active at any other time.',

  personalInfo:
    'The package name and display name of the app in the foreground (e.g., "Instagram"); whether the short-form video player of YouTube or Instagram is currently open (a yes/no on-screen state, never the video, caption or post itself); and — only inside supported browsers — the address of the page currently open, which is compared on-device against the block list you configured. This identifies which app and page you are on, not any content within it.',

  userGeneratedContent:
    "A local log of blocked-app attempts during focus sessions, containing the app name, timestamp, and session reference. This is user-facing history shown in the app's distraction log.",

  notCollected:
    'The Accessibility Service does NOT read keystrokes, passwords, form fields, messages, or the content of pages you visit. It reads exactly three things: which app is in the foreground; whether the YouTube Shorts or Instagram Reels player is open (from on-screen element identifiers and their bounds — never the video, caption, comments or post itself); and, in a supported browser, the page address, used solely to match your own website block list and known adult domains. It is never active outside a user-started focus session and can be revoked at any time via Android Settings.',

  storage:
    'All data stays on-device. Nothing is transmitted to our servers or third parties.',

  prominentDisclosure:
    'Users are shown a full-screen prominent disclosure explicitly naming these data types before the permission is requested, and must explicitly acknowledge it before the permission dialog opens.',
} as const
