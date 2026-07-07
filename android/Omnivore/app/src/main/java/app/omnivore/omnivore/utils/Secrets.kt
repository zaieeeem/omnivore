package app.omnivore.omnivore.utils

import android.content.Context
import app.omnivore.omnivore.R

/**
 * Helpers for gating proprietary third-party integrations (PSPDFKit, Intercom,
 * PostHog) so the self-hosted build works without any of their credentials.
 *
 * The corresponding string resources default to the "unset" placeholder (see
 * res/values/strings.xml). Any integration whose key is unset is treated as
 * disabled and must be skipped rather than initialized, so a dummy/placeholder
 * key can never crash the app.
 */

private val PLACEHOLDER_SECRETS = setOf("", "unset", "dummy")

/** True when a secret string looks like a real, configured credential. */
fun isSecretConfigured(value: String?): Boolean =
    !value.isNullOrBlank() && value.trim() !in PLACEHOLDER_SECRETS

/**
 * True only when a real PSPDFKit license is present. PSPDFKit license keys are
 * long signed blobs, so we additionally require a plausible length. When this
 * returns false the app falls back to the system PDF viewer.
 */
fun hasPspdfkitLicense(context: Context): Boolean {
    val key = context.getString(R.string.pspdfkit_license_key)
    return isSecretConfigured(key) && key.length > 30
}
