import httpContext from 'express-http-context2'
import { PostHog } from 'posthog-node'
import { env } from '../env'

interface AnalyticEvent {
  distinctId: string
  event: string
  properties?: Record<string | number, any>
}

interface AnalyticClient {
  capture: (event: AnalyticEvent) => void
  shutdownAsync?: () => Promise<void>
}

class PostHogClient implements AnalyticClient {
  private client: PostHog | null

  constructor(apiKey: string | null) {
    // Only construct a real PostHog client when an API key is configured and
    // we're not running locally. Self-hosted deploys leave POSTHOG_API_KEY
    // empty, so analytics becomes an inert no-op.
    if (apiKey && !env.dev.isLocal) {
      this.client = new PostHog(apiKey)
    } else {
      this.client = null
    }
  }

  capture({ distinctId, event, properties }: AnalyticEvent) {
    if (!this.client) {
      return
    }

    // // get client from request context
    // const client = httpContext.get<string>('client') || 'other'
    // this.client.capture({
    //   distinctId,
    //   event,
    //   properties: {
    //     ...properties,
    //     client,
    //     env: env.server.apiEnv,
    //   },
    // })
  }

  async shutdownAsync() {
    if (!this.client) {
      return
    }
    return this.client.shutdownAsync()
  }
}

export const analytics = new PostHogClient(env.posthog.apiKey)
