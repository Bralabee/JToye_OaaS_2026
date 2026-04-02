export interface PublicShop {
  slug: string
  name: string
  description: string | null
  address: string | null
  logoUrl: string | null
  bannerUrl: string | null
  phone: string | null
  email: string | null
  latitude: number | null
  longitude: number | null
  openingHours: Record<string, string> | null
  deliveryInfo: string | null
  minimumOrderPennies: number
  tags: string | null
}

export interface PublicProduct {
  id: string
  title: string
  description: string | null
  imageUrl: string | null
  imageUrls: string[]
  ingredientsText: string
  allergenMask: number
  pricePennies: number
  category: string | null
  dietaryTags: string | null
  preparationTimeMinutes: number | null
  featured: boolean
}

export type ProductsByCategory = Record<string, PublicProduct[]>
