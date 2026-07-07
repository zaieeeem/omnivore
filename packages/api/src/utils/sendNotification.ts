import { applicationDefault, initializeApp } from 'firebase-admin/app'
import {
  BatchResponse,
  getMessaging,
  Message,
  MulticastMessage,
} from 'firebase-admin/messaging'
import { env } from '../env'
import { analytics } from './analytics'
import { logger } from './logger'

export type PushNotificationType = 'newsletter' | 'reminder' | 'rule'

let firebaseInitialized = false
if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
  try {
    // getting credentials from App Engine
    initializeApp({
      credential: applicationDefault(),
    })
    firebaseInitialized = true
  } catch (error) {
    logger.error('Failed to initialize Firebase Admin', error)
  }
}

export const sendPushNotification = async (
  userId: string,
  message: Message,
  type: PushNotificationType
): Promise<string | undefined> => {
  if (!firebaseInitialized) {
    logger.debug('Firebase not initialized; skipping sendPushNotification')
    return undefined
  }

  try {
    analytics.capture({
      distinctId: userId,
      event: 'notification_sent',
      properties: {
        type,
        multicast: false,
        env: env.server.apiEnv,
      },
    })

    const res = await getMessaging().send(message)
    logger.info(res)

    return res
  } catch (err) {
    logger.error('firebase cloud message error: ', err)

    return undefined
  }
}

export const sendMulticastPushNotifications = async (
  userId: string,
  message: MulticastMessage,
  type: PushNotificationType
): Promise<BatchResponse | undefined> => {
  if (!firebaseInitialized) {
    logger.debug('Firebase not initialized; skipping sendMulticastPushNotifications')
    return undefined
  }

  try {
    analytics.capture({
      distinctId: userId,
      event: 'notification_sent',
      properties: {
        type,
        multicast: true,
        env: env.server.apiEnv,
      },
    })

    logger.info('sending multicast message: ', message)
    const res = await getMessaging().sendEachForMulticast(message)
    logger.info('send notification result: ', res.responses)

    return res
  } catch (err) {
    logger.error('firebase cloud message error: ', err)

    return undefined
  }
}

export const sendBatchPushNotifications = async (
  messages: Message[]
): Promise<BatchResponse | undefined> => {
  if (!firebaseInitialized) {
    logger.debug('Firebase not initialized; skipping sendBatchPushNotifications')
    return undefined
  }

  try {
    const res = await getMessaging().sendEach(messages)
    logger.info(`success count: ${res.successCount}`)

    return res
  } catch (err) {
    logger.error('firebase cloud message error: ', err)

    return undefined
  }
}
