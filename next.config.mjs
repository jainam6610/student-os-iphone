import { readFileSync } from 'node:fs'

// Single source of truth for the version shown in Settings / About.
// Bump `version` in package.json + `versionName`/`versionCode` in
// android/app/build.gradle together when releasing.
const { version } = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'))

// GitHub Pages project site → repo name. Set GITHUB_PAGES_REPO at deploy time.
// Locally: undefined → no basePath. On GitHub Actions: set to the repo name.
const githubPagesRepo = process.env.GITHUB_PAGES_REPO
const isGitHubPages = !!githubPagesRepo

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'export',
  env: {
    NEXT_PUBLIC_APP_VERSION: version,
    NEXT_PUBLIC_BASE_PATH: isGitHubPages ? `/${githubPagesRepo}` : '',
  },
  images: {
    unoptimized: true,
  },
  trailingSlash: true,
  // GitHub Pages project sites serve from /<repo>/
  basePath: isGitHubPages ? `/${githubPagesRepo}` : '',
  assetPrefix: isGitHubPages ? `/${githubPagesRepo}/` : '',
  // Ensure clean chunk names and stable builds
  productionBrowserSourceMaps: false,
  // Prevent accidental server dependencies
  experimental: {
    // Ensure no server components sneak in
    esmExternals: true,
  },
  // Static export disables headers/rewrites/redirects automatically.
  // For Cloudflare Pages, use public/_headers and public/_redirects instead.
}

export default nextConfig
