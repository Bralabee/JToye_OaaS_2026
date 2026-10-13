/**
 * RED-phase placeholder (37-46 Task 1): the generalised show-once dialog lands here in the GREEN
 * commit. Until then this path re-exports the webhook dialog unchanged, so the new suite loads and
 * fails on its assertions rather than on a missing module.
 */
export { SecretRevealDialog, default } from "@/components/dashboard/webhooks/SecretRevealDialog"
