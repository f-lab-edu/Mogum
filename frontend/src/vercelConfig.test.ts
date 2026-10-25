// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

type RoutingRule = { source: string; destination: string }
type VercelConfig = { rewrites: RoutingRule[]; redirects?: RoutingRule[] }

function readConfig(): VercelConfig {
  return JSON.parse(readFileSync(new URL('../vercel.json', import.meta.url), 'utf8'))
}

describe('Vercel static SPA configuration', () => {
  it('contains only one catch-all rewrite to index.html after the default filesystem check', () => {
    const config = readConfig()

    expect(Object.keys(config)).toEqual(['rewrites'])
    expect(config.rewrites).toEqual([{ source: '/(.*)', destination: '/index.html' }])
  })

  it('never proxies or redirects requests to an external origin', () => {
    const config = readConfig()

    for (const rule of [...config.rewrites, ...(config.redirects ?? [])]) {
      expect(rule.destination).not.toMatch(/^(?:https?:)?\/\//i)
    }
  })

  it('has no special routing for API or OAuth paths', () => {
    const config = readConfig()

    expect(config.redirects ?? []).toEqual([])
    for (const rule of config.rewrites) {
      expect(rule.source).not.toMatch(/^\/(?:api|auth)(?:\/|$)/)
      expect(rule.destination).toBe('/index.html')
    }
  })
})
