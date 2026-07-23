import { ReviewQueue } from "@/components/dashboard/media/ReviewQueue"

/**
 * Vendor media review/rejection queue (Phase 24 IMG-04). Authenticated dashboard
 * surface (SEO N/A) — the tenant-scoped review queue is fetched client-side via
 * the RLS-isolated GET /api/v1/media/review-queue.
 */
export default function MediaReviewPage() {
  return <ReviewQueue />
}
