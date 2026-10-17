import apiClient from "@/lib/api-client"
import type {
  CreateFulfilmentSlotRequest,
  FulfilmentSlotDto,
  WhatsAppPreOrderLinkDto,
} from "@/types/api"

/**
 * Scheduled fulfilment slot & WhatsApp pre-order link API helpers for `/dashboard/shops`
 * (ADR-0007 / GAP-GTM-02, GAP-GTM-06).
 *
 * Kept separate from `lib/shops-api.ts` because `lib/shops-api.ts` is part of the
 * kitchen route's `kitchenSources()` scan (`kitchen-route-calls.test.ts`), where every
 * endpoint must stay at the `STAFF` server floor (D-08 owner ruling 2026-10-10).
 * Creating and deactivating slots require `SHOP_MANAGER` on the server.
 */

/** GET /api/v1/shops/{id}/slots (STAFF on the shop): list scheduled fulfilment slots. */
export async function fetchShopFulfilmentSlots(shopId: string): Promise<FulfilmentSlotDto[]> {
  const res = await apiClient.get<FulfilmentSlotDto[]>(`/api/v1/shops/${shopId}/slots`)
  return res.data ?? []
}

/** POST /api/v1/shops/{id}/slots (SHOP_MANAGER on the shop): create a scheduled fulfilment slot. */
export async function createShopFulfilmentSlot(
  shopId: string,
  req: CreateFulfilmentSlotRequest
): Promise<FulfilmentSlotDto> {
  const res = await apiClient.post<FulfilmentSlotDto>(`/api/v1/shops/${shopId}/slots`, req)
  return res.data
}

/** DELETE /api/v1/shops/{id}/slots/{slotId} (SHOP_MANAGER on the shop): deactivate a scheduled fulfilment slot. */
export async function deactivateShopFulfilmentSlot(
  shopId: string,
  slotId: string
): Promise<FulfilmentSlotDto> {
  const res = await apiClient.delete<FulfilmentSlotDto>(`/api/v1/shops/${shopId}/slots/${slotId}`)
  return res.data
}

/** GET /api/v1/shops/{id}/whatsapp-preorder-link[?slotId=...] (STAFF on the shop): generate shareable WhatsApp pre-order link. */
export async function fetchWhatsAppPreOrderLink(
  shopId: string,
  slotId?: string | null
): Promise<WhatsAppPreOrderLinkDto> {
  const query = slotId && slotId.trim() ? `?slotId=${encodeURIComponent(slotId.trim())}` : ""
  const res = await apiClient.get<WhatsAppPreOrderLinkDto>(
    `/api/v1/shops/${shopId}/whatsapp-preorder-link${query}`
  )
  return res.data
}
