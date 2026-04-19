"use client"

import { useEffect, useState } from "react"
import { motion } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import apiClient from "@/lib/api-client"
import { useToast } from "@/hooks/use-toast"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import Link from "next/link"
import { Package, Plus, Pencil, Trash2, AlertCircle, Search, FileText, Star, Eye, EyeOff, ImageIcon, Sparkles, Check, Upload } from "lucide-react"
import { ImageUploader, type AiSuggestions } from "@/components/ui/image-uploader"
import { SafeImage } from "@/components/ui/safe-image"
import { Pagination } from "@/components/ui/pagination"
import type { Product, CreateProductRequest, Shop } from "@/types/api"
import {
  ALLERGENS,
  hasAllergen,
  toggleAllergen,
  getAllergenNames,
} from "@/types/api"

const productSchema = z.object({
  sku: z.string().min(1, "SKU is required").max(50, "SKU too long"),
  title: z.string().min(1, "Title is required").max(200, "Title too long"),
  ingredientsText: z
    .string()
    .min(1, "Ingredients are required")
    .max(1000, "Ingredients text too long"),
  pricePounds: z
    .string()
    .min(1, "Price is required")
    .refine((val) => !isNaN(parseFloat(val)) && parseFloat(val) >= 0, "Price must be a non-negative number"),
})

type ProductFormData = z.infer<typeof productSchema>

function AiSuggestionRow({ label, value, onAccept }: { label: string; value: string; onAccept: () => void }) {
  return (
    <div className="flex items-start gap-2 bg-surface-card rounded-sm px-2 py-1.5 border border-subtle">
      <div className="flex-1 min-w-0">
        <span className="text-[10px] font-semibold text-accent-editorial uppercase tracking-[0.08em]">{label}</span>
        <p className="text-xs text-ink-primary line-clamp-2">{value}</p>
      </div>
      <button
        type="button"
        onClick={onAccept}
        className="flex-shrink-0 mt-1 inline-flex items-center gap-1 rounded-sm bg-accent-editorial text-ink-on-accent px-2 py-0.5 text-[10px] font-semibold transition-colors hover:brightness-95"
      >
        <Check className="h-2.5 w-2.5" />
        Apply
      </button>
    </div>
  )
}

const PAGE_SIZE = 20

export default function ProductsPage() {
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState("")
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [editingProduct, setEditingProduct] = useState<Product | null>(null)
  const [deletingProduct, setDeletingProduct] = useState<Product | null>(null)
  const [allergenMask, setAllergenMask] = useState(0)
  const [available, setAvailable] = useState(true)
  const [featured, setFeatured] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [aiSuggestions, setAiSuggestions] = useState<AiSuggestions | null>(null)
  const [shops, setShops] = useState<Shop[]>([])
  const [selectedShopId, setSelectedShopId] = useState<string>("")
  const [trackInventory, setTrackInventory] = useState(false)
  const [quantityInStock, setQuantityInStock] = useState<number>(0)
  const { toast } = useToast()

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
  } = useForm<ProductFormData>({
    resolver: zodResolver(productSchema),
  })

  useEffect(() => {
    fetchProducts()
    fetchShops()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage])

  const fetchShops = async () => {
    try {
      const response = await apiClient.get("/api/v1/shops?size=100")
      setShops(response.data.content || [])
    } catch {
      // Shops are optional — fail silently
    }
  }

  useEffect(() => {
    if (searchQuery.length >= 2) {
      const timer = setTimeout(() => searchProducts(searchQuery), 300)
      return () => clearTimeout(timer)
    } else if (searchQuery.length === 0) {
      fetchProducts()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchQuery])

  const fetchProducts = async () => {
    try {
      setLoading(true)
      const response = await apiClient.get(
        `/api/v1/products?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`
      )
      setProducts(response.data.content || [])
      setTotalPages(response.data.totalPages || 0)
      setTotalElements(response.data.totalElements || 0)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load products"
      toast({
        variant: "destructive",
        title: "Error loading products",
        description: errorMessage,
      })
    } finally {
      setLoading(false)
    }
  }

  const searchProducts = async (query: string) => {
    try {
      const response = await apiClient.get(`/api/v1/products/search?q=${encodeURIComponent(query)}`)
      setProducts(response.data || [])
      setTotalPages(1)
      setTotalElements(response.data?.length || 0)
    } catch {
      // Fall back to showing all products
    }
  }

  const openCreateDialog = () => {
    setEditingProduct(null)
    reset({ sku: "", title: "", ingredientsText: "", pricePounds: "" })
    setAllergenMask(0)
    setAvailable(true)
    setFeatured(false)
    setSelectedShopId("")
    setTrackInventory(false)
    setQuantityInStock(0)
    setAiSuggestions(null)
    setDialogOpen(true)
  }

  const openEditDialog = (product: Product) => {
    setEditingProduct(product)
    setValue("sku", product.sku)
    setValue("title", product.title)
    setValue("ingredientsText", product.ingredientsText)
    setValue("pricePounds", ((product.pricePennies || 0) / 100).toFixed(2))
    setAllergenMask(product.allergenMask)
    setAvailable(product.available ?? true)
    setFeatured(product.featured ?? false)
    setSelectedShopId(product.shopId || "")
    setTrackInventory(product.quantityInStock != null)
    setQuantityInStock(product.quantityInStock ?? 0)
    setAiSuggestions(null)
    setDialogOpen(true)
  }

  const openDeleteDialog = (product: Product) => {
    setDeletingProduct(product)
    setDeleteDialogOpen(true)
  }

  const toggleAllergenBit = (bit: number) => {
    setAllergenMask(toggleAllergen(allergenMask, bit))
  }

  const onSubmit = async (data: ProductFormData) => {
    try {
      setSubmitting(true)

      // Read storefront fields from form elements (not zod-validated)
      const form = document.getElementById("product-form") as HTMLFormElement
      const descEl = form?.querySelector<HTMLTextAreaElement>("[name=description]")
      const imageUrlEl = form?.querySelector<HTMLInputElement>("[name=imageUrl]")
      const categoryEl = form?.querySelector<HTMLInputElement>("[name=category]")
      const displayOrderEl = form?.querySelector<HTMLInputElement>("[name=displayOrder]")
      const prepTimeEl = form?.querySelector<HTMLInputElement>("[name=preparationTimeMinutes]")
      const dietaryTagsEl = form?.querySelector<HTMLInputElement>("[name=dietaryTags]")

      const payload: CreateProductRequest = {
        sku: data.sku,
        title: data.title,
        ingredientsText: data.ingredientsText,
        allergenMask,
        pricePennies: Math.round(parseFloat(data.pricePounds) * 100),
        available,
        featured,
        description: descEl?.value || undefined,
        imageUrl: imageUrlEl?.value || undefined,
        category: categoryEl?.value || undefined,
        displayOrder: displayOrderEl?.value ? parseInt(displayOrderEl.value) : undefined,
        preparationTimeMinutes: prepTimeEl?.value ? parseInt(prepTimeEl.value) : undefined,
        dietaryTags: dietaryTagsEl?.value || undefined,
        shopId: selectedShopId || undefined,
        quantityInStock: trackInventory ? quantityInStock : null,
      }

      if (editingProduct) {
        // Update existing product
        await apiClient.put(`/api/v1/products/${editingProduct.id}`, payload)
        toast({
          title: "Product updated",
          description: `${data.title} has been updated successfully.`,
        })
      } else {
        // Create new product
        await apiClient.post("/api/v1/products", payload)
        toast({
          title: "Product created",
          description: `${data.title} has been created successfully.`,
        })
      }

      setDialogOpen(false)
      reset()
      setAllergenMask(0)
      if (currentPage === 0) fetchProducts()
      else setCurrentPage(0)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : `Failed to ${editingProduct ? "update" : "create"} product`
      toast({
        variant: "destructive",
        title: editingProduct ? "Error updating product" : "Error creating product",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    if (!deletingProduct) return

    try {
      setSubmitting(true)
      await apiClient.delete(`/api/v1/products/${deletingProduct.id}`)
      toast({
        title: "Product deleted",
        description: `${deletingProduct.title} has been deleted successfully.`,
      })
      setDeleteDialogOpen(false)
      setDeletingProduct(null)
      if (currentPage === 0) fetchProducts()
      else setCurrentPage(0)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to delete product"
      toast({
        variant: "destructive",
        title: "Error deleting product",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-brand-primary motion-reduce:animate-none" aria-label="Loading"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex items-center justify-between"
      >
        <div>
          <h1 className="font-display text-4xl font-semibold tracking-tight text-ink-primary">Products</h1>
          <p className="mt-2 text-ink-secondary">
            Manage your product catalog with allergen information
          </p>
        </div>
        <div className="flex gap-2">
          <Link href="/dashboard/products/import">
            <Button variant="secondary" className="gap-2">
              <Upload className="h-4 w-4" />
              Bulk Import
            </Button>
          </Link>
          <Button onClick={openCreateDialog} variant="primary" className="gap-2">
            <Plus className="h-4 w-4" />
            Add Product
          </Button>
        </div>
      </motion.div>

      {/* Products Table */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <div>
              <CardTitle>All Products</CardTitle>
              <CardDescription>
                {totalElements} product{totalElements !== 1 ? "s" : ""} in total
              </CardDescription>
            </div>
            <div className="relative w-[220px]">
              <Search className="absolute left-2 top-2.5 h-4 w-4 text-ink-tertiary" aria-hidden="true" />
              <Input
                placeholder="Search products..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-8"
              />
            </div>
          </CardHeader>
          <CardContent>
            {products.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Package className="mb-4 h-12 w-12 text-ink-tertiary" aria-hidden="true" />
                <h3 className="mb-2 font-display text-lg font-semibold text-ink-primary">
                  No products yet
                </h3>
                <p className="mb-4 text-sm text-ink-tertiary">
                  Get started by creating your first product
                </p>
                <Button onClick={openCreateDialog} variant="secondary">
                  <Plus className="mr-2 h-4 w-4" />
                  Add Product
                </Button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>SKU</TableHead>
                      <TableHead>Title</TableHead>
                      <TableHead>Category</TableHead>
                      <TableHead>Allergens</TableHead>
                      <TableHead className="text-right">Price</TableHead>
                      <TableHead className="text-center">Status</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {products.map((product) => {
                      const allergenNames = getAllergenNames(product.allergenMask)
                      return (
                        <motion.tr
                          key={product.id}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          className="group"
                        >
                          <TableCell className="font-mono text-sm font-medium">
                            {product.sku}
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center gap-2">
                              <SafeImage
                                src={product.imageUrl}
                                alt={product.title}
                                className="h-8 w-8 rounded-sm object-cover"
                                fallbackClassName="h-8 w-8 rounded-sm bg-accent-editorial-subtle"
                                fallbackIcon={<Package className="h-4 w-4 text-accent-editorial" />}
                              />
                              <div>
                                <div className="font-medium text-ink-primary">{product.title}</div>
                                <div className="line-clamp-1 text-xs text-ink-tertiary">
                                  {product.ingredientsText}
                                </div>
                              </div>
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center gap-1.5">
                              {product.category ? (
                                <Badge variant="subtle" size="sm">{product.category}</Badge>
                              ) : (
                                <span className="text-xs text-ink-tertiary">—</span>
                              )}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="flex flex-wrap gap-1">
                              {allergenNames.length === 0 ? (
                                <span className="text-sm text-ink-tertiary">
                                  No allergens
                                </span>
                              ) : (
                                allergenNames.map((name) => {
                                  const allergen = ALLERGENS.find(
                                    (a) => a.name === name
                                  )
                                  return (
                                    <Badge
                                      key={name}
                                      variant="warning"
                                      size="sm"
                                    >
                                      <span className="mr-1" aria-hidden="true">{allergen?.icon}</span>
                                      {name}
                                    </Badge>
                                  )
                                })
                              )}
                            </div>
                          </TableCell>
                          <TableCell numeric className="font-semibold text-ink-primary">
                            {product.pricePennies != null
                              ? `£${(product.pricePennies / 100).toFixed(2)}`
                              : "—"}
                          </TableCell>
                          <TableCell className="text-center">
                            <div className="flex items-center justify-center gap-1.5">
                              {product.available ? (
                                <span title="Available"><Eye className="h-3.5 w-3.5 text-success" aria-label="Available" /></span>
                              ) : (
                                <span title="Unavailable"><EyeOff className="h-3.5 w-3.5 text-ink-tertiary" aria-label="Unavailable" /></span>
                              )}
                              {product.featured && (
                                <span title="Featured"><Star className="h-3.5 w-3.5 text-warning fill-current" aria-label="Featured" /></span>
                              )}
                            </div>
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="flex justify-end gap-2">
                              <Button
                                variant="ghost"
                                size="iconSm"
                                onClick={async () => {
                                  try {
                                    const res = await apiClient.get(`/api/v1/products/${product.id}/label`, { responseType: "blob" })
                                    const url = URL.createObjectURL(res.data)
                                    const a = document.createElement("a")
                                    a.href = url
                                    a.download = `label-${product.sku}.pdf`
                                    a.click()
                                    URL.revokeObjectURL(url)
                                  } catch {
                                    toast({ variant: "destructive", title: "Error", description: "Failed to download label" })
                                  }
                                }}
                                title="Download allergen label"
                                aria-label="Download allergen label"
                              >
                                <FileText className="h-4 w-4" />
                              </Button>
                              <Button
                                variant="ghost"
                                size="iconSm"
                                onClick={() => openEditDialog(product)}
                                aria-label="Edit product"
                              >
                                <Pencil className="h-4 w-4" />
                              </Button>
                              <Button
                                variant="ghost"
                                size="iconSm"
                                onClick={() => openDeleteDialog(product)}
                                className="text-danger hover:bg-danger-subtle"
                                aria-label="Delete product"
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </div>
                          </TableCell>
                        </motion.tr>
                      )
                    })}
                  </TableBody>
                </Table>
              </div>
            )}
            <Pagination
              currentPage={currentPage}
              totalPages={totalPages}
              totalElements={totalElements}
              pageSize={PAGE_SIZE}
              onPageChange={setCurrentPage}
            />
          </CardContent>
        </Card>
      </motion.div>

      {/* Create/Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editingProduct ? "Edit Product" : "Create New Product"}
            </DialogTitle>
            <DialogDescription>
              {editingProduct
                ? "Update the product details below."
                : "Add a new product to your catalog."}
            </DialogDescription>
          </DialogHeader>
          <form id="product-form" onSubmit={handleSubmit(onSubmit)} className="space-y-6">
            <h4 className="text-[11px] font-semibold text-ink-tertiary uppercase tracking-[0.08em]">Product Details</h4>
            <div className="space-y-2">
              <Label htmlFor="sku">SKU</Label>
              <Input
                id="sku"
                placeholder="e.g., PROD-001"
                {...register("sku")}
              />
              {errors.sku && (
                <p className="text-sm text-danger">{errors.sku.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="title">Product Title</Label>
              <Input
                id="title"
                placeholder="e.g., Chocolate Chip Cookies"
                {...register("title")}
              />
              {errors.title && (
                <p className="text-sm text-danger">{errors.title.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="ingredientsText">Ingredients</Label>
              <textarea
                id="ingredientsText"
                placeholder="e.g., Flour, sugar, butter, chocolate chips..."
                className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                {...register("ingredientsText")}
              />
              {errors.ingredientsText && (
                <p className="text-sm text-danger">
                  {errors.ingredientsText.message}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="pricePounds">Price (£)</Label>
              <Input
                id="pricePounds"
                type="number"
                step="0.01"
                min="0"
                placeholder="e.g., 12.50"
                {...register("pricePounds")}
              />
              {errors.pricePounds && (
                <p className="text-sm text-danger">{errors.pricePounds.message}</p>
              )}
            </div>

            {/* Storefront Presentation */}
            <div className="space-y-3">
              <h4 className="text-[11px] font-semibold text-ink-tertiary uppercase tracking-[0.08em]">Storefront Presentation</h4>
              <div className="space-y-2">
                <Label htmlFor="description">Customer Description</Label>
                <textarea
                  id="description"
                  name="description"
                  placeholder="Describe this product for customers..."
                  defaultValue={editingProduct?.description || ""}
                  rows={2}
                  className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
              {editingProduct ? (
                <ImageUploader
                  currentImageUrl={editingProduct.imageUrl}
                  uploadUrl={`/api/v1/products/${editingProduct.id}/image`}
                  onUploadComplete={(url) => {
                    setEditingProduct({ ...editingProduct, imageUrl: url })
                    fetchProducts()
                  }}
                  onAiSuggestions={(suggestions) => {
                    setAiSuggestions(suggestions)
                    toast({ title: "AI Analysis Complete", description: `Identified: ${suggestions.identifiedName || "Unknown"}` })
                  }}
                  onRemove={async () => {
                    try {
                      await apiClient.delete(`/api/v1/products/${editingProduct.id}/image`)
                      setEditingProduct({ ...editingProduct, imageUrl: null })
                      setAiSuggestions(null)
                      fetchProducts()
                    } catch {
                      toast({ variant: "destructive", title: "Error", description: "Failed to remove image" })
                    }
                  }}
                  label="Product Image"
                />
              ) : (
                <div className="flex items-center gap-2 rounded-md border border-dashed border-subtle bg-surface-subtle px-3 py-3 text-sm text-ink-tertiary">
                  <ImageIcon className="h-4 w-4" aria-hidden="true" />
                  <span>Save the product first, then add an image</span>
                </div>
              )}

              {/* AI Suggestions Panel */}
              {aiSuggestions && aiSuggestions.confidence && aiSuggestions.confidence > 0.3 && (
                <div className="rounded-md border border-subtle bg-accent-editorial-subtle p-3 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Sparkles className="h-4 w-4 text-accent-editorial" aria-hidden="true" />
                      <span className="text-sm font-semibold text-ink-primary">AI Suggestions</span>
                      <span className="text-xs text-ink-secondary font-mono tabular-nums">
                        {Math.round((aiSuggestions.confidence || 0) * 100)}% confidence
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={() => setAiSuggestions(null)}
                      className="text-xs text-ink-tertiary hover:text-ink-primary transition-colors"
                    >
                      Dismiss
                    </button>
                  </div>
                  {aiSuggestions.cuisineOrigin && (
                    <p className="text-xs text-ink-secondary">Cuisine: {aiSuggestions.cuisineOrigin}</p>
                  )}
                  <div className="grid grid-cols-1 gap-2">
                    {aiSuggestions.identifiedName && (
                      <AiSuggestionRow
                        label="Product Name"
                        value={aiSuggestions.identifiedName}
                        onAccept={() => {
                          setValue("title", aiSuggestions.identifiedName!)
                          toast({ title: "Applied", description: `Title set to "${aiSuggestions.identifiedName}"` })
                        }}
                      />
                    )}
                    {aiSuggestions.description && (
                      <AiSuggestionRow
                        label="Description"
                        value={aiSuggestions.description}
                        onAccept={() => {
                          const el = document.querySelector<HTMLTextAreaElement>("[name=description]")
                          if (el) el.value = aiSuggestions.description!
                          toast({ title: "Applied", description: "Description updated" })
                        }}
                      />
                    )}
                    {aiSuggestions.ingredients && (
                      <AiSuggestionRow
                        label="Ingredients"
                        value={aiSuggestions.ingredients}
                        onAccept={() => {
                          setValue("ingredientsText", aiSuggestions.ingredients!)
                          toast({ title: "Applied", description: "Ingredients updated" })
                        }}
                      />
                    )}
                    {aiSuggestions.category && (
                      <AiSuggestionRow
                        label="Category"
                        value={aiSuggestions.category}
                        onAccept={() => {
                          const el = document.querySelector<HTMLInputElement>("[name=category]")
                          if (el) el.value = aiSuggestions.category!
                          toast({ title: "Applied", description: `Category set to "${aiSuggestions.category}"` })
                        }}
                      />
                    )}
                    {aiSuggestions.dietaryTags && aiSuggestions.dietaryTags.length > 0 && (
                      <AiSuggestionRow
                        label="Dietary Tags"
                        value={aiSuggestions.dietaryTags.join(", ")}
                        onAccept={() => {
                          const el = document.querySelector<HTMLInputElement>("[name=dietaryTags]")
                          if (el) el.value = aiSuggestions.dietaryTags!.join(", ")
                          toast({ title: "Applied", description: "Dietary tags updated" })
                        }}
                      />
                    )}
                    {aiSuggestions.allergenWarnings && aiSuggestions.allergenWarnings.length > 0 && (
                      <div className="text-xs text-ink-primary bg-warning-subtle rounded-sm px-2 py-1.5">
                        <AlertCircle className="inline h-3 w-3 mr-1" aria-hidden="true" />
                        Allergen warnings: {aiSuggestions.allergenWarnings.join(", ")}
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Keep hidden input for backwards compatibility with form submission */}
              <input type="hidden" name="imageUrl" value={editingProduct?.imageUrl || ""} />
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="category">Category</Label>
                  <Input id="category" name="category" placeholder="e.g., Mains" defaultValue={editingProduct?.category || ""} list="category-list" />
                  <datalist id="category-list">
                    {Array.from(new Set(products.map(p => p.category).filter(Boolean))).map(cat => (
                      <option key={cat} value={cat!} />
                    ))}
                  </datalist>
                </div>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="shopId">Shop Assignment</Label>
                <select
                  id="shopId"
                  value={selectedShopId}
                  onChange={(e) => setSelectedShopId(e.target.value)}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                >
                  <option value="">All Shops</option>
                  {shops.map((shop) => (
                    <option key={shop.id} value={shop.id}>{shop.name}</option>
                  ))}
                </select>
              </div>
              <div className="space-y-1.5">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={trackInventory}
                    onChange={(e) => setTrackInventory(e.target.checked)}
                    className="h-4 w-4 rounded border-slate-300"
                  />
                  <span className="text-sm font-medium">Track inventory</span>
                </label>
                {trackInventory && (
                  <div className="mt-1.5">
                    <Label htmlFor="quantityInStock">Stock Quantity</Label>
                    <Input
                      id="quantityInStock"
                      type="number"
                      min="0"
                      value={quantityInStock}
                      onChange={(e) => setQuantityInStock(parseInt(e.target.value) || 0)}
                    />
                  </div>
                )}
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="displayOrder">Display Order</Label>
                  <Input id="displayOrder" name="displayOrder" type="number" min="0" placeholder="0" defaultValue={editingProduct?.displayOrder ?? 0} />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="preparationTimeMinutes">Prep Time (min)</Label>
                  <Input id="preparationTimeMinutes" name="preparationTimeMinutes" type="number" min="0" placeholder="15" defaultValue={editingProduct?.preparationTimeMinutes || ""} />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="dietaryTags">Dietary Tags</Label>
                  <Input id="dietaryTags" name="dietaryTags" placeholder="Vegan, GF" defaultValue={editingProduct?.dietaryTags || ""} />
                </div>
              </div>
              <div className="flex gap-6">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={available} onChange={(e) => setAvailable(e.target.checked)} className="h-4 w-4 rounded border-slate-300" />
                  <span className="text-sm">Available</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={featured} onChange={(e) => setFeatured(e.target.checked)} className="h-4 w-4 rounded border-slate-300" />
                  <span className="text-sm">Featured (Popular)</span>
                </label>
              </div>
            </div>

            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <AlertCircle className="h-4 w-4 text-brand-primary" aria-hidden="true" />
                <Label>Allergens</Label>
              </div>
              <p className="text-sm text-ink-secondary">
                Select all allergens present in this product
              </p>
              <div className="grid grid-cols-2 gap-3 rounded-md border border-subtle p-4 bg-surface-subtle">
                {ALLERGENS.map((allergen) => (
                  <label
                    key={allergen.bit}
                    className="flex items-center gap-3 cursor-pointer rounded-sm p-2 hover:bg-surface-card transition-colors"
                  >
                    <input
                      type="checkbox"
                      checked={hasAllergen(allergenMask, allergen.bit)}
                      onChange={() => toggleAllergenBit(allergen.bit)}
                      className="h-4 w-4 rounded border-border-tone text-brand-primary focus:ring-brand-primary"
                    />
                    <span className="text-lg" aria-hidden="true">{allergen.icon}</span>
                    <span className="text-sm font-medium">{allergen.name}</span>
                  </label>
                ))}
              </div>
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setDialogOpen(false)}
                disabled={submitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={submitting}>
                {submitting
                  ? editingProduct
                    ? "Updating..."
                    : "Creating..."
                  : editingProduct
                  ? "Update Product"
                  : "Create Product"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Product</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingProduct?.title}</span>? This
              action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDeleteDialogOpen(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={submitting}
            >
              {submitting ? "Deleting..." : "Delete Product"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
